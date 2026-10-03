package edu.cit.sibi.channel;

import edu.cit.sibi.channel.ChannelStore.ChannelOrder;
import edu.cit.sibi.shop.exception.OrderAlreadyCancelledException;
import edu.cit.sibi.shop.model.OrderStatus;
import edu.cit.sibi.shop.service.OrderService;
import edu.cit.sibi.supplier.SupplierGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Task 6: backordered orders wait for stock. When a LegacySupply delivery has
 * restocked Inventory, the oldest backorders are filled first and resolved
 * ACCEPTED. One that can't be filled and has no restock coming is CANCELLED.
 */
@Component
class BackorderResolver {

    private static final Logger log = LoggerFactory.getLogger(BackorderResolver.class);

    private static final int RESTOCK_TARGET = 10;

    private final ChannelStore store;
    private final OrderService orderService;
    private final SupplierGateway supplier;
    private final TiangeClient client;
    private final StockSyncListener stockSync;

    /** Consecutive checks that found no restock on the way (guards the instant between "delivered" and "restocked"). */
    private final Map<String, Integer> misses = new HashMap<>();

    BackorderResolver(ChannelStore store, OrderService orderService, SupplierGateway supplier,
                      TiangeClient client, StockSyncListener stockSync) {
        this.store = store;
        this.orderService = orderService;
        this.supplier = supplier;
        this.client = client;
        this.stockSync = stockSync;
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    void resolve() {
        for (ChannelOrder co : store.openBackorders()) {
            try {
                resolveOne(co);
            } catch (RuntimeException e) {
                log.warn("Backorder {} not resolved yet: {}", co.tianggeOrderId(), e.getMessage());
            }
        }
    }

    private void resolveOne(ChannelOrder co) {
        String tid = co.tianggeOrderId();
        String outcome;

        // Our saved purchase-order statuses can lag LegacySupply: refresh the ones this order waits on.
        for (String product : orderService.requiredUnits(co.shopOrderId()).keySet()) {
            supplier.refreshInbound(product);
        }

        // About to accept this order from delivered stock: Tiangge must already have been SENT a figure
        // big enough to cover it, otherwise the acceptance is an oversold order.
        if (orderService.shortProducts(co.shopOrderId()).isEmpty()
                && !stockSync.awaitPublishedAtLeast(orderService.requiredUnits(co.shopOrderId()), 6000)) {
            log.info("Backorder {}: Tiangge has not confirmed our delivered stock yet, will try again", tid);
            return;
        }

        OrderStatus status = orderService.fulfilBackorder(co.shopOrderId());
        if (status == OrderStatus.CONFIRMED) {
            outcome = "ACCEPTED";
        } else if (status == OrderStatus.BACKORDERED) {
            List<String> shorts = orderService.shortProducts(co.shopOrderId());
            Map<String, Integer> need = orderService.requiredUnits(co.shopOrderId());
            for (String product : shorts) {
                if (!supplier.hasRestockOnTheWay(product)) {
                    // every open backorder must have a purchase order coming for what it lacks
                    supplier.ensureRestock(product, Math.max(RESTOCK_TARGET, need.getOrDefault(product, 1)));
                }
            }
            boolean stillComing = shorts.stream().allMatch(supplier::hasRestockOnTheWay);
            if (stillComing) {
                misses.remove(tid);
                return;
            }
            if (misses.merge(tid, 1, Integer::sum) < 2) {
                return;
            }
            orderService.cancelOrder(co.shopOrderId());
            outcome = "CANCELLED";
        } else {
            outcome = "CANCELLED";
        }

        final String finalOutcome = outcome;
        try {
            // Resolution delivery + "resolved" flag together, in strict order with stock figures.
            Supplier<Object> deliver = () -> {
                try {
                    client.resolve(tid, finalOutcome);
                } catch (HttpClientErrorException e) {
                    if (e.getStatusCode().value() != 409) {
                        throw e;
                    }
                    log.info("Tiangge says {} is no longer backordered", tid);
                }
                store.markResolved(tid);
                return null;
            };
            if ("ACCEPTED".equals(finalOutcome)) {
                // Same protection as an ACCEPTED decision: exclusive of stock figures, covered by Tiangge's own view.
                stockSync.sendAcceptance(tid, orderService.requiredUnits(co.shopOrderId()), deliver);
            } else {
                stockSync.sendInOrder(deliver);
            }
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 404) {
                abandonUnknown(co);   // Tiangge no longer has this order: stop retrying it for ever
                return;
            }
            throw e;
        }
        misses.remove(tid);
        log.info("Backorder {} (shop order {}) resolved {}", tid, co.shopOrderId(), outcome);
        if ("ACCEPTED".equals(outcome)) {
            stockSync.publishAll();   // resolution delivered: show the reduced stock
        }
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
        misses.remove(tid);
        log.warn("Tiangge no longer knows order {}: cancelled shop order {} and closed it", tid, co.shopOrderId());
    }
}