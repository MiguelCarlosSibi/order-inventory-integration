package edu.cit.sibi.supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Part D: "Never lose a reorder. If LegacySupply is unavailable, keep the
 * reorder as PENDING in supplier_orders and have a scheduled job send it
 * later." This is that job. It reuses the row's original (never-changing)
 * requestId, so a resend is safe even if the first attempt actually DID
 * reach LegacySupply and only the response was lost — LegacySupply itself
 * dedupes on X-Request-Id.
 */
@Component
class PendingReorderRetryJob {

    private static final Logger log = LoggerFactory.getLogger(PendingReorderRetryJob.class);

    private final SupplierOrderRepository repository;
    private final SupplierGatewayImpl gateway;

    PendingReorderRetryJob(SupplierOrderRepository repository, SupplierGatewayImpl gateway) {
        this.repository = repository;
        this.gateway = gateway;
    }

    @Scheduled(fixedDelayString = "${supplier.reorder.pending-retry-interval-ms:60000}")
    void resendPending() {
        List<SupplierOrder> pending = repository.findByStatus(SupplierOrderStatus.PENDING);
        if (pending.isEmpty()) {
            return;
        }
        log.info("Resending {} PENDING reorder(s)", pending.size());
        for (SupplierOrder order : pending) {
            gateway.attemptSend(order, order.getProductId());
        }
    }
}
