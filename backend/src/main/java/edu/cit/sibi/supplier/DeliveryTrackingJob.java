package edu.cit.sibi.supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Part E: polls open purchase orders, maps LegacySupply's StatusCode to our
 * own {@link SupplierOrderStatus}, and — when an order newly reaches
 * DELIVERED — publishes {@link SupplierOrderDeliveredEvent} rather than
 * calling Inventory directly. Inventory listens for that event and
 * restocks; this job (and the rest of the supplier module) never imports
 * InventoryService.
 */
@Component
class DeliveryTrackingJob {

    private static final Logger log = LoggerFactory.getLogger(DeliveryTrackingJob.class);

    private static final List<SupplierOrderStatus> OPEN_STATUSES =
            List.of(SupplierOrderStatus.ACCEPTED, SupplierOrderStatus.PICKING, SupplierOrderStatus.SHIPPED);

    private final SupplierOrderRepository repository;
    private final LegacySupplyClient client;
    private final ProductCatalogMapping catalogMapping;
    private final ApplicationEventPublisher eventPublisher;

    /** True once one full pass over the open purchase orders has completed since the app started. */
    private volatile boolean checkedSinceStart = false;

    DeliveryTrackingJob(SupplierOrderRepository repository, LegacySupplyClient client,
                        ProductCatalogMapping catalogMapping, ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.client = client;
        this.catalogMapping = catalogMapping;
        this.eventPublisher = eventPublisher;
    }

    /**
     * After a restart our saved purchase-order statuses can be stale (LegacySupply keeps delivering
     * while the app is off). Order decisions wait for this to turn true so we never backorder
     * against a purchase order that has in fact already been delivered.
     */
    boolean checkedSinceStart() {
        return checkedSinceStart;
    }

    @Scheduled(fixedDelayString = "${supplier.reorder.delivery-poll-interval-ms:30000}")
    void pollOpenOrders() {
        List<SupplierOrder> open = repository.findByStatusIn(OPEN_STATUSES);
        if (open.isEmpty()) {
            checkedSinceStart = true;
            return;
        }
        log.info("Polling {} open supplier order(s)", open.size());
        boolean allChecked = true;

        for (SupplierOrder order : open) {
            try {
                PurchaseOrderStatusXml status = client.getStatus(order.getPoNumber());
                SupplierOrderStatus newStatus = SupplierGatewayImpl.mapStatus(status.statusCode);
                SupplierOrderStatus previousStatus = order.getStatus();

                if (newStatus != previousStatus) {
                    order.setStatus(newStatus);
                    repository.save(order);
                    log.info("Order {} ({}): {} -> {}", order.getBuyerRef(), order.getPoNumber(), previousStatus, newStatus);

                    if (newStatus == SupplierOrderStatus.DELIVERED) {
                        // Publish the units LegacySupply actually shipped
                        // (cases x PackSize), not the units originally
                        // requested — a rounded-up order ships a full case,
                        // so this can be more than what was asked for.
                        int unitsShipped = catalogMapping.casesToUnits(order.getProductId(), order.getCases());
                        eventPublisher.publishEvent(new SupplierOrderDeliveredEvent(
                                order.getProductId(), unitsShipped, order.getId()));
                    }
                }
            } catch (LegacySupplyUnavailableException e) {
                // Transient - this order stays at its current status and
                // gets picked up again on the next poll.
                allChecked = false;
                log.warn("Could not check status for {}: {}", order.getBuyerRef(), e.getMessage());
            } catch (LegacySupplyRejectedException e) {
                // The order itself was rejected/not found on LegacySupply's
                // side after the fact - flag it rather than poll forever.
                order.setStatus(SupplierOrderStatus.FAILED);
                repository.save(order);
                log.error("Order {} ({}) now permanently failed: {}", order.getBuyerRef(), order.getPoNumber(), e.getMessage());
            }
        }
        if (allChecked) {
            checkedSinceStart = true;
        }
    }
}