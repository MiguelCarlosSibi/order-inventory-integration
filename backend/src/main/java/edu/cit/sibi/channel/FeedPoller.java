package edu.cit.sibi.channel;

import edu.cit.sibi.channel.ChannelStore.ChannelOrder;
import edu.cit.sibi.inventory.InventoryService;
import edu.cit.sibi.inventory.dto.ReservationResult;
import java.util.concurrent.ConcurrentHashMap;
import edu.cit.sibi.shop.dto.OrderItemRequest;
import edu.cit.sibi.shop.dto.OrderResponse;
import edu.cit.sibi.shop.exception.OrderAlreadyCancelledException;
import edu.cit.sibi.shop.exception.StockChangedException;
import edu.cit.sibi.shop.model.OrderStatus;
import edu.cit.sibi.shop.service.OrderService;
import edu.cit.sibi.supplier.SupplierGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Tasks 4 and 5: polls the Tiangge feed, turns each order into exactly one
 * shop order, reports the decision, and handles customer cancellations.
 * The cursor and every handled eventId live in the database, so a restart
 * continues where it stopped and redelivered events are ignored.
 */
@Component
class FeedPoller {

    private static final Logger log = LoggerFactory.getLogger(FeedPoller.class);

    /** Same rule as the Lab 3 low-stock policy: restock up to twice the threshold (5). */
    private static final int RESTOCK_TARGET = 10;

    // 8 workers: during a flash sale, external calls can each take seconds, and more headroom
    // keeps later-queued events inside the 60 s decision budget.
    private final ExecutorService pool = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "feed-worker");
        t.setDaemon(true);
        return t;
    });

    /**
     * Held only while an order is checked against stock/promised units and committed (milliseconds),
     * so two orders can never count the same incoming units. Never held during a supplier or Tiangge call.
     */
    private final ReentrantLock placeLock = new ReentrantLock();
    /** Cancellations whose confirmation is still pending: their stock hold stays until Tiangge confirms. */
    private final Map<String, Set<String>> cancelHeld = new ConcurrentHashMap<>();
    private final TiangeClient client;
    private final ChannelStore store;
    private final OrderService orderService;
    private final InventoryService inventory;
    private final SupplierGateway supplier;
    private final StockSyncListener stockSync;
    private final TransactionTemplate tx;

    FeedPoller(TiangeClient client, ChannelStore store, OrderService orderService, InventoryService inventory,
               SupplierGateway supplier, StockSyncListener stockSync, TransactionTemplate tx) {
        this.client = client;
        this.store = store;
        this.orderService = orderService;
        this.inventory = inventory;
        this.supplier = supplier;
        this.stockSync = stockSync;
        this.tx = tx;
    }

    private final Instant startedAt = Instant.now();

    /**
     * After a restart our saved purchase-order statuses can be stale (LegacySupply keeps delivering
     * while we are off). Deciding an order against a stale "restock on the way" would backorder it
     * with no open purchase order behind it, so wait for the first full status refresh. The wait is
     * capped, so an unreachable supplier can never freeze order handling (60 s decision deadline).
     */
    private boolean supplierStatusesFresh() {
        return supplier.deliveriesUpToDate() || Duration.between(startedAt, Instant.now()).toSeconds() > 20;
    }

    @Scheduled(fixedDelay = 2000, initialDelay = 3000)
    void poll() {
        if (!supplierStatusesFresh()) {
            return;
        }
        try {
            sendUnsentDecisions();
            long cursor = store.cursor();
            while (true) {
                FeedResponse page = client.fetchFeed(cursor, 50);
                if (page == null || page.events() == null || page.events().isEmpty()) {
                    return;
                }

                // One group per Tiangge order, so a placement and its cancellation stay in order.
                Map<String, List<FeedEvent>> groups = new LinkedHashMap<>();
                for (FeedEvent ev : page.events()) {
                    groups.computeIfAbsent(ev.orderId(), k -> new ArrayList<>()).add(ev);
                }
                List<Future<?>> futures = new ArrayList<>();
                for (List<FeedEvent> group : groups.values()) {
                    futures.add(pool.submit(() -> group.forEach(this::handle)));
                }

                boolean failed = false;
                for (Future<?> f : futures) {
                    try {
                        f.get();
                    } catch (ExecutionException e) {
                        failed = true;
                        log.warn("Event handling failed, page will be replayed: {}", e.getCause().getMessage());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (failed) {
                    return;   // cursor stays; handled events are skipped on replay
                }
                long last = page.events().get(page.events().size() - 1).seq();
                store.saveCursor(last);
                cursor = last;
            }
        } catch (RuntimeException e) {
            log.warn("Feed poll stopped, will retry: {}", e.getMessage());
        }
    }

    private void handle(FeedEvent ev) {
        if (store.eventSeen(ev.eventId())) {
            log.info("Duplicate event {} (order {}) ignored", ev.eventId(), ev.orderId());
            return;
        }
        switch (ev.type()) {
            case "ORDER_PLACED" -> handlePlaced(ev);
            case "ORDER_CANCELLED" -> handleCancelled(ev);
            default -> log.warn("Unknown feed event type {}", ev.type());
        }
        store.markEventSeen(ev);
    }

    // ---------------------------------------------------------------- orders

    private void handlePlaced(FeedEvent ev) {
        String tid = ev.orderId();
        if (store.find(tid).isPresent()) {
            log.info("Order {} already handled (arrived again as event {}), no second order created", tid, ev.eventId());
            return;
        }

        Map<String, Integer> merged = new LinkedHashMap<>();
        if (ev.lines() != null) {
            for (FeedLine line : ev.lines()) {
                merged.merge(line.sellerSku(), line.qty(), Integer::sum);
            }
        }
        List<OrderItemRequest> items = merged.entrySet().stream()
                .map(e -> new OrderItemRequest(e.getKey(), e.getValue())).toList();

        ensureRestocks(items);

        // Stock, if this reserves any, is already published by InventoryChangedEvent
        // the moment the order commits - independent of whether the decision below succeeds.
        OrderResponse response = placeAtomically(ev, items);
        log.info("Tiangge order {} -> shop order {} = {}", tid, response.orderId(), response.status());
        sendDecision(tid);
    }

    /** If we are short and nothing is coming, order from LegacySupply BEFORE deciding, so we can backorder. */
    private void ensureRestocks(List<OrderItemRequest> items) {
        for (OrderItemRequest item : items) {
            try {
                ensureSupplyFor(item);
            } catch (NoSuchElementException unknownProduct) {
                // Not our product: the order will be rejected.
            } catch (RuntimeException e) {
                log.warn("Could not check restock for {}: {}", item.productId(), e.getMessage());
            }
        }
    }

    /** Thrown inside the transaction to undo a rejection that a restock could turn into a backorder. */
    private static final class RestockNeeded extends RuntimeException {
        final List<OrderItemRequest> shortLines;

        RestockNeeded(List<OrderItemRequest> shortLines) {
            super("restock needed", null, false, false);
            this.shortLines = shortLines;
        }
    }

    /** The shop order and our record of the Tiangge order commit together, or not at all. */
    private OrderResponse placeAtomically(FeedEvent ev, List<OrderItemRequest> items) {
        for (int attempt = 1; ; attempt++) {
            final boolean lastTry = attempt >= 3;
            try {
                placeLock.lock();
                try {
                    return tx.execute(status -> {
                    OrderResponse r = orderService.placeOrder(items, restockCovers(items), store::backorderedUnits);
                    if (!lastTry && "REJECTED".equals(r.status())) {
                        List<OrderItemRequest> shortLines = shortLines(items);
                        if (shortLines != null) {
                            // Short on stock with no restock coming (other orders took it first).
                            // Undo this rejection, order a restock, then decide again.
                            throw new RestockNeeded(shortLines);
                        }
                    }
                    store.insertOrder(ev.orderId(), r.orderId(), Instant.parse(ev.placedAt()), decisionOf(r.status()));
                    return r;
                    });
                } finally {
                    placeLock.unlock();
                }
            } catch (RestockNeeded e) {
                restockFor(e.shortLines);
            } catch (StockChangedException e) {
                if (lastTry) {
                    throw e;
                }
            }
        }
    }

    /** null if a product is unknown to us (that order can never be filled), else the lines we are short on right now. */
    private List<OrderItemRequest> shortLines(List<OrderItemRequest> items) {
        List<OrderItemRequest> out = new ArrayList<>();
        for (OrderItemRequest item : items) {
            ReservationResult check = inventory.checkAvailability(item.productId(), item.quantity());
            if (check.item() == null) {
                return null;
            }
            if (!check.success()) {
                out.add(item);
            }
        }
        return out;
    }

    /**
     * Makes sure incoming stock covers this order PLUS every backorder already waiting for the same
     * product. Returns true if nothing was needed or a purchase order is on its way.
     */
    private boolean ensureSupplyFor(OrderItemRequest item) {
        String pid = item.productId();
        int stock = inventory.getItem(pid).stock();
        int promised = store.backorderedUnits(pid);
        if (stock - promised >= item.quantity()) {
            return true;
        }
        // Our saved purchase-order statuses can lag LegacySupply by a poll interval: a delivery that
        // already happened there would still look "on the way" here. Check live before relying on it.
        supplier.refreshInbound(pid);
        stock = inventory.getItem(pid).stock();
        if (stock - promised >= item.quantity()) {
            return true;
        }
        int needed = Math.max(Math.max(RESTOCK_TARGET, item.quantity()) - stock,
                promised + item.quantity() - stock);
        boolean onTheWay = supplier.ensureSupply(pid, needed);
        log.info("Short on {} (have {}, {} promised to backorders, need {}): inbound target {} units, restock on the way = {}",
                pid, stock, promised, item.quantity(), needed, onTheWay);
        return onTheWay;
    }

    /**
     * An order may only be BACKORDERED if stock + units already on the way, minus what earlier
     * backorders are waiting for, still covers it. Otherwise we would promise stock we will not have.
     */
    private Predicate<String> restockCovers(List<OrderItemRequest> items) {
        return productId -> {
            try {
                int wanted = items.stream().filter(i -> i.productId().equals(productId))
                        .mapToInt(OrderItemRequest::quantity).sum();
                int stock = inventory.getItem(productId).stock();
                return stock + supplier.inboundUnits(productId) - store.backorderedUnits(productId) >= wanted;
            } catch (RuntimeException e) {
                return false;
            }
        };
    }

    private void restockFor(List<OrderItemRequest> lines) {
        for (OrderItemRequest item : lines) {
            try {
                ensureSupplyFor(item);
            } catch (RuntimeException e) {
                log.warn("Could not order restock for {}: {}", item.productId(), e.getMessage());
            }
        }
    }

    private static String decisionOf(String orderStatus) {
        return switch (orderStatus) {
            case "CONFIRMED" -> "ACCEPTED";
            case "BACKORDERED" -> "BACKORDERED";
            default -> "REJECTED";
        };
    }

    private void sendUnsentDecisions() {
        for (ChannelOrder co : store.unsentDecisions()) {
            sendDecision(co.tianggeOrderId());
        }
    }

    /** Just before a BACKORDERED decision goes out, make sure a purchase order really is coming. */
    private ChannelOrder revalidated(ChannelOrder co) {
        if (!"BACKORDERED".equals(co.decision())) {
            return co;
        }
        String tid = co.tianggeOrderId();
        try {
            Map<String, Integer> need = orderService.requiredUnits(co.shopOrderId());
            for (String product : need.keySet()) {
                supplier.refreshInbound(product);   // never judge "restock on the way" from a stale status
            }
            List<String> shorts = new ArrayList<>(orderService.shortProducts(co.shopOrderId()));
            // Stock that is physically here but owed to OLDER backorders is not ours to take: this
            // order stays BACKORDERED instead of jumping the queue.
            for (Map.Entry<String, Integer> line : need.entrySet()) {
                if (!shorts.contains(line.getKey())
                        && inventory.getItem(line.getKey()).stock()
                                - store.backorderedUnitsBefore(line.getKey(), co.shopOrderId()) < line.getValue()) {
                    shorts.add(line.getKey());
                }
            }
            if (shorts.isEmpty()) {
                // The stock arrived since placement and nobody older is waiting for it. Never accept
                // from stock Tiangge has not been SENT yet: that acceptance would be an oversold order.
                // Stay BACKORDERED instead; BackorderResolver accepts it once Tiangge has the figure.
                if (!stockSync.awaitPublishedAtLeast(need, 6000)) {
                    log.info("Backorder {}: Tiangge has not been sent our delivered stock yet, not accepting it now", tid);
                    return co;
                }
                if (orderService.fulfilBackorder(co.shopOrderId()) == OrderStatus.CONFIRMED) {
                    store.changeDecision(tid, "ACCEPTED");
                    return store.find(tid).orElseThrow();
                }
                return co;
            }
            for (String product : shorts) {
                if (!supplier.hasRestockOnTheWay(product)) {
                    int owed = store.backorderedUnitsBefore(product, co.shopOrderId());
                    int wanted = need.getOrDefault(product, 1);
                    int stock = inventory.getItem(product).stock();
                    supplier.ensureSupply(product, Math.max(Math.max(RESTOCK_TARGET, wanted), owed + wanted - stock));
                    if (!supplier.hasRestockOnTheWay(product)) {
                        if (Duration.between(co.placedAt(), Instant.now()).toSeconds() > 30) {
                            return co;   // do not risk the 60 s deadline
                        }
                        log.warn("Backorder {}: no restock on the way for {} yet, holding the decision", tid, product);
                        return null;
                    }
                }
            }
            return co;
        } catch (RuntimeException e) {
            log.warn("Could not re-check backorder {} before deciding: {}", tid, e.getMessage());
            return null;
        }
    }

    /** Safe to repeat: Tiangge returns the order unchanged for the same decision and shopOrderId. */
    private boolean sendDecision(String tid) {
        ChannelOrder current = store.find(tid).orElseThrow();
        if (current.decisionSent()) {
            return true;
        }
        final ChannelOrder co = revalidated(current);
        if (co == null) {
            return false;   // not safe to announce yet; retried on the next poll
        }
        try {
            // Delivering the decision and recording "decision sent" happen together, in strict order
            // with stock updates (see StockSyncListener#sendInOrder).
            Supplier<Object> deliver = () -> {
                try {
                    client.decide(tid, co.decision(), "SO-" + co.shopOrderId(), null);
                } catch (HttpClientErrorException e) {
                    if (e.getStatusCode().value() != 409) {
                        throw e;
                    }
                    log.warn("Tiangge already holds a different decision for {}", tid);
                }
                store.markDecisionSent(tid);
                return null;
            };
            if ("ACCEPTED".equals(co.decision())) {
                // Exclusive of stock figures for these products, and only if Tiangge's own view covers it.
                stockSync.sendAcceptance(tid, orderService.requiredUnits(co.shopOrderId()), deliver);
            } else {
                stockSync.sendInOrder(deliver);
            }
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 404) {
                abandonUnknown(co);   // Tiangge no longer has this order: stop retrying it for ever
                return false;
            }
            log.error("Tiangge refused the decision for {}: {}", tid, e.getResponseBodyAsString());
            return false;
        } catch (RuntimeException e) {
            log.warn("Decision for {} not delivered yet, will retry: {}", tid, e.getMessage());
            return false;
        }
        long seconds = Duration.between(co.placedAt(), Instant.now()).toSeconds();
        log.info("Decided {} as {} (shop order {}) {} s after placement{}", tid, co.decision(),
                co.shopOrderId(), seconds, seconds > 60 ? "  *** LATE ***" : "");
        if ("ACCEPTED".equals(co.decision())) {
            stockSync.publishAll();   // Tiangge has the decision now: show the reduced stock
        }
        return true;
    }

    /**
     * Tiangge answered order_not_found: it does not know this order any more, so nothing will ever
     * ship for it. Give back any stock we reserved for it, close our record, and stop retrying.
     */
    private void abandonUnknown(ChannelOrder co) {
        String tid = co.tianggeOrderId();
        try {
            orderService.cancelOrder(co.shopOrderId());   // restocks if it was already filled
        } catch (OrderAlreadyCancelledException alreadyDone) {
            // nothing left to give back
        }
        store.markDecisionSent(tid);
        store.markResolved(tid);
        store.markCancelConfirmed(tid);
        stockSync.clearAmbiguous(tid);
        log.warn("Tiangge no longer knows order {}: cancelled shop order {} and closed it", tid, co.shopOrderId());
    }

    // ---------------------------------------------------------- cancellations

    private void handleCancelled(FeedEvent ev) {
        String tid = ev.orderId();
        Optional<ChannelOrder> found = store.find(tid);
        if (found.isEmpty()) {
            log.warn("Cancellation for unknown order {} ignored", tid);
            return;
        }
        ChannelOrder co = found.get();
        if (co.cancelConfirmed()) {
            log.info("Cancellation of {} already confirmed", tid);
            return;
        }
        if (!co.decisionSent()) {
            sendDecision(tid);
        }

        // The manual: confirm FIRST, then publish the restocked figure. Hold stock for these
        // products until Tiangge has confirmed. On a replay the hold already exists: never hold twice.
        Set<String> heldProducts = cancelHeld.get(tid);
        if (heldProducts == null) {
            heldProducts = new java.util.HashSet<>();
            try {
                heldProducts.addAll(orderService.requiredUnits(co.shopOrderId()).keySet());
            } catch (RuntimeException e) {
                log.info("DIAG cancel order={} shopOrder={} could not read requiredUnits: {}",
                        tid, co.shopOrderId(), e.getMessage());
            }
            log.info("DIAG cancel order={} shopOrder={} heldProducts={}", tid, co.shopOrderId(), heldProducts);
            stockSync.holdPublishing(heldProducts);
            cancelHeld.put(tid, heldProducts);
        }

        try {
            // Restocks Inventory (its InventoryChangedEvent is held back while the embargo is on).
            orderService.cancelOrder(co.shopOrderId());
        } catch (OrderAlreadyCancelledException alreadyDone) {
            // a replay: the restock already happened
        }

        try {
            client.confirmCancellation(tid);
        } catch (HttpClientErrorException e) {
            int code = e.getStatusCode().value();
            if (code != 409 && code != 404) {
                // Permanent refusal: retrying can never help and would block the whole feed page.
                log.error("Tiangge refused the cancellation confirmation for {}: {}", tid, e.getResponseBodyAsString());
                cancelHeld.remove(tid);
                stockSync.releasePublishing(heldProducts);
                stockSync.publishAll();
                return;
            }
            // 409/404, e.g. a timed-out first attempt that Tiangge did process: settled.
            log.warn("Tiangge answered {} to the cancellation confirmation of {}; treating it as settled: {}",
                    code, tid, e.getResponseBodyAsString());
        }
        // Timeouts and 5xx are NOT caught: they propagate, the event is replayed on the next poll,
        // and the stock hold stays in place. Only a confirmed cancellation reaches the lines below.

        store.markCancelConfirmed(tid);
        long seconds = Duration.between(Instant.parse(ev.cancelledAt()), Instant.now()).toSeconds();
        log.info("Cancellation of {} confirmed {} s after the customer cancelled", tid, seconds);
        cancelHeld.remove(tid);
        stockSync.releasePublishing(heldProducts);
        stockSync.publishAll();
    }
}
