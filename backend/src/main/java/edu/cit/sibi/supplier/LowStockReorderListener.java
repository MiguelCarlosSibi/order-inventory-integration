package edu.cit.sibi.supplier;

import edu.cit.sibi.shop.event.LowStockEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lab 3's auto-reorder, now run after the order transaction commits and on
 * its own thread, so a slow or failing LegacySupply never delays a customer
 * order (this is what INTEGRATION.md describes). Skips products that already
 * have an open reorder.
 */
@Component
class LowStockReorderListener {

    private static final Logger log = LoggerFactory.getLogger(LowStockReorderListener.class);

    private final SupplierGateway supplierGateway;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "low-stock-reorder");
        t.setDaemon(true);
        return t;
    });

    LowStockReorderListener(SupplierGateway supplierGateway) {
        this.supplierGateway = supplierGateway;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onLowStock(LowStockEvent event) {
        // Reorder policy: bring stock back up to double the threshold.
        int unitsNeeded = event.threshold() * 2 - event.currentStock();
        if (unitsNeeded <= 0) {
            return;
        }
        executor.submit(() -> {
            try {
                log.info("Low stock on {} ({} left, threshold {}) - ensuring a reorder of {} units",
                        event.productId(), event.currentStock(), event.threshold(), unitsNeeded);
                supplierGateway.ensureRestock(event.productId(), unitsNeeded);
            } catch (RuntimeException e) {
                log.warn("Low-stock reorder for {} failed: {}", event.productId(), e.getMessage());
            }
        });
    }
}