package edu.cit.sibi.supplier;

import edu.cit.sibi.shop.event.LowStockEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Lab 2's auto-reorder rule only logged "reorder needed" (see
 * NotificationEventListener, which still does that — unchanged). Lab 3
 * adds this listener alongside it to actually place a purchase order.
 * <p>
 * This listener lives in the supplier module (not shop or inventory)
 * specifically so that neither of those modules needs to import anything
 * from edu.cit.sibi.supplier at all, satisfying the rule that "Order and
 * Inventory modules must not import anything that describes LegacySupply"
 * as literally as possible — they don't import the supplier package in
 * any form. The only cross-module import here is LowStockEvent, the same
 * "depend on event classes only" pattern Notification already uses.
 */
@Component
class LowStockReorderListener {

    private static final Logger log = LoggerFactory.getLogger(LowStockReorderListener.class);

    private final SupplierGateway supplierGateway;

    LowStockReorderListener(SupplierGateway supplierGateway) {
        this.supplierGateway = supplierGateway;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    void onLowStock(LowStockEvent event) {
        // Reorder policy: bring stock back up to double the threshold.
        // Simple and documented here rather than configurable — adjust if
        // your instructor wants a different rule, and note the choice in
        // INTEGRATION.md.
        int targetStock = event.threshold() * 2;
        int unitsNeeded = targetStock - event.currentStock();
        if (unitsNeeded <= 0) {
            return;
        }

        log.info("Low stock on {} ({} left, threshold {}) - requesting reorder of {} units",
                event.productId(), event.currentStock(), event.threshold(), unitsNeeded);
        ReorderResult result = supplierGateway.requestReorder(event.productId(), unitsNeeded);
        log.info("Reorder {} for {}: status={}, poNumber={}",
                result.buyerRef(), event.productId(), result.status(), result.poNumber());
    }
}
