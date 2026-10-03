package edu.cit.sibi.channel;

import edu.cit.sibi.channel.ChannelStore.ChannelOrder;
import edu.cit.sibi.inventory.InventoryService;
import edu.cit.sibi.shop.dto.OrderItemRequest;
import edu.cit.sibi.shop.dto.OrderResponse;
import edu.cit.sibi.shop.exception.OrderAlreadyCancelledException;
import edu.cit.sibi.shop.exception.StockChangedException;
import edu.cit.sibi.shop.service.OrderService;
import edu.cit.sibi.supplier.SupplierGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import edu.cit.sibi.inventory.dto.ReservationResult;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
    // 8, not 4: during a flash sale / hands-off test, Tiangge places orders
    // faster than usual while external calls (decide, ensureRestock) can
    // each take seconds under slow/degraded conditions - more headroom
    // here is what keeps later-queued events inside the 60s decision budget.
    private final ExecutorService pool = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "feed-worker");
        t.setDaemon(true);
        return t;
    });

    private final TiangeClient client;
    private final ChannelStore store;
    private final OrderService orderService;
    private final InventoryService inventory;
    private final SupplierGateway supplier;
    private final StockSyncListener stockSync;
    private final TransactionTemplate tx;
    private final Instant startedAt = Instant.now();

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

    @Scheduled(fixedDelay = 2000, initialDelay = 3000)
    void poll() {
        // After a restart, wait until our purchase-order statuses are fresh (but never longer than
        // 30 s), so we don't backorder against a purchase order that was delivered while we were off.
        if (!supplier.deliveriesUpToDate() && Duration.between(startedAt, Instant.now()).toSeconds() < 30) {
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
                int stock = inventory.getItem(item.productId()).stock();
                if (stock < item.quantity()) {
                    int units = Math.max(RESTOCK_TARGET, item.quantity()) - stock;
                    boolean onTheWay = supplier.ensureRestock(item.productId(), units);
                    log.info("Short on {} (have {}, need {}): restock on the way = {}",
                            item.productId(), stock, item.quantity(), onTheWay);
                }
            } catch (NoSuchElementException unknownProduct) {
                // Not our product: the order will be rejected.
            } catch (RuntimeException e) {
                log.warn("Could not check restock for {}: {}", item.productId(), e.getMessage());
            }
        }
    }

    /** The shop order and our record of the Tiangge order commit together, or not at all. */
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
                return tx.execute(status -> {
                    OrderResponse r = orderService.placeOrder(items, supplier::hasRestockOnTheWay);
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

    private void restockFor(List<OrderItemRequest> lines) {
        for (OrderItemRequest item : lines) {
            try {
                int stock = inventory.getItem(item.productId()).stock();
                int units = Math.max(RESTOCK_TARGET, item.quantity()) - stock;
                if (units > 0) {
                    boolean onTheWay = supplier.ensureRestock(item.productId(), units);
                    log.info("Stock for {} ran out while deciding (have {}, need {}): restock on the way = {}",
                            item.productId(), stock, item.quantity(), onTheWay);
                }
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

    /** Safe to repeat: Tiangge returns the order unchanged for the same decision and shopOrderId. */
    private boolean sendDecision(String tid) {
        ChannelOrder co = store.find(tid).orElseThrow();
        if (co.decisionSent()) {
            return true;
        }
        try {
            client.decide(tid, co.decision(), "SO-" + co.shopOrderId(), null);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 404) {
                abandonUnknown(co);   // Tiangge no longer has this order: stop retrying it for ever
                return false;
            }
            if (e.getStatusCode().value() != 409) {
                log.error("Tiangge refused the decision for {}: {}", tid, e.getResponseBodyAsString());
                return false;
            }
            log.warn("Tiangge already holds a different decision for {}", tid);
        } catch (RuntimeException e) {
            log.warn("Decision for {} not delivered yet, will retry: {}", tid, e.getMessage());
            return false;
        }
        store.markDecisionSent(tid);
        long seconds = Duration.between(co.placedAt(), Instant.now()).toSeconds();
        log.info("Decided {} as {} (shop order {}) {} s after placement{}", tid, co.decision(),
                co.shopOrderId(), seconds, seconds > 60 ? "  *** LATE ***" : "");
        if ("ACCEPTED".equals(co.decision())) {
            // Tiangge has the decision now: show the reduced stock for exactly the products on this order.
            stockSync.publishProducts(store.productsOf(co.shopOrderId()));
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

        try {
            try {
                // Restocks Inventory, which publishes the new stock on its own
                // via InventoryChangedEvent the moment this commits - independent
                // of whether the confirmation call below succeeds.
                orderService.cancelOrder(co.shopOrderId());
            } catch (OrderAlreadyCancelledException alreadyDone) {
                // a retry after a failed confirmation: the restock already happened
            }
            client.confirmCancellation(tid);
            store.markCancelConfirmed(tid);
            long seconds = Duration.between(Instant.parse(ev.cancelledAt()), Instant.now()).toSeconds();
            log.info("Cancellation of {} confirmed {} s after the customer cancelled", tid, seconds);
            stockSync.publishProducts(store.productsOf(co.shopOrderId()));   // the manual: confirm first, THEN publish the restocked figure
        } catch (HttpClientErrorException e) {
            log.error("Tiangge refused the cancellation confirmation for {}: {}", tid, e.getResponseBodyAsString());
        }
    }
}