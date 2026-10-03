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
import java.util.Map;

/**
 * Task 6: backordered orders wait for stock. When a LegacySupply delivery has
 * restocked Inventory, the oldest backorders are filled first and resolved
 * ACCEPTED. One that can't be filled and has no restock coming is CANCELLED.
 */
@Component
class BackorderResolver {

    private static final Logger log = LoggerFactory.getLogger(BackorderResolver.class);

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

    /**
     * Stock that fulfilBackorder()/cancelOrder() changes below is already
     * published by InventoryChangedEvent the moment each commits -
     * independent of whether the resolve() call to Tiangge afterward
     * succeeds. (An earlier version held that publish until after
     * client.resolve() succeeded and threw it away on any failure - the
     * same bug fixed in FeedPoller, but worse here: there was no later
     * sweep to catch what got dropped.)
     */
    private void resolveOne(ChannelOrder co) {
        String tid = co.tianggeOrderId();
        String outcome;
        // Tiangge must already know the delivered stock before we accept against it.
        stockSync.awaitCaughtUp(6000);
        OrderStatus status = orderService.fulfilBackorder(co.shopOrderId());
        if (status == OrderStatus.CONFIRMED) {
            outcome = "ACCEPTED";
        } else if (status == OrderStatus.BACKORDERED) {
            boolean stillComing = orderService.shortProducts(co.shopOrderId()).stream()
                    .allMatch(supplier::hasRestockOnTheWay);
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
        try {
            client.resolve(tid, outcome);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 404) {
                abandonUnknown(co);   // Tiangge no longer has this order: stop retrying it for ever
                return;
            }
            if (e.getStatusCode().value() != 409) {
                throw e;
            }
            log.info("Tiangge says {} is no longer backordered", tid);
        }
        store.markResolved(tid);
        misses.remove(tid);
        log.info("Backorder {} (shop order {}) resolved {}", tid, co.shopOrderId(), outcome);
        if ("ACCEPTED".equals(outcome)) {
            stockSync.publishProducts(store.productsOf(co.shopOrderId()));   // resolution delivered: show the reduced stock
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
        misses.remove(tid);
        log.warn("Tiangge no longer knows order {}: cancelled shop order {} and closed it", tid, co.shopOrderId());
    }
}