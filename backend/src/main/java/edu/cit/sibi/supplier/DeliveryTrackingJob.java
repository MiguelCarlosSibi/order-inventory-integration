package edu.cit.sibi.supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
class DeliveryTrackingJob {

    private static final Logger log = LoggerFactory.getLogger(DeliveryTrackingJob.class);

    private static final List<SupplierOrderStatus> OPEN_STATUSES =
            List.of(SupplierOrderStatus.ACCEPTED, SupplierOrderStatus.PICKING, SupplierOrderStatus.SHIPPED);

    private final SupplierOrderRepository repository;
    private final LegacySupplyClient client;
    private final ApplicationEventPublisher eventPublisher;
    private final ProductCatalogMapping catalogMapping;

    DeliveryTrackingJob(SupplierOrderRepository repository, LegacySupplyClient client,
                        ApplicationEventPublisher eventPublisher, ProductCatalogMapping catalogMapping) {
        this.repository = repository;
        this.client = client;
        this.eventPublisher = eventPublisher;
        this.catalogMapping = catalogMapping;
    }

    @Scheduled(fixedDelayString = "${supplier.reorder.delivery-poll-interval-ms:60000}")
    void pollOpenOrders() {
        List<SupplierOrder> open = repository.findByStatusIn(OPEN_STATUSES);
        if (open.isEmpty()) {
            return;
        }
        log.info("Polling {} open supplier order(s)", open.size());

        for (SupplierOrder order : open) {
            try {
                PurchaseOrderStatusXml status = client.getStatus(order.getPoNumber());
                SupplierOrderStatus newStatus = SupplierGatewayImpl.mapStatus(status.statusCode);
                SupplierOrderStatus previousStatus = order.getStatus();

                if (newStatus != previousStatus) {
                    if (newStatus == SupplierOrderStatus.DELIVERED) {
                        // Publish BEFORE saving: if the restock fails, the order stays open and is retried next poll.
                        int unitsDelivered = catalogMapping.casesToUnits(order.getProductId(), order.getCases());
                        eventPublisher.publishEvent(new SupplierOrderDeliveredEvent(
                                order.getProductId(), unitsDelivered, order.getId()));
                    }
                    order.setStatus(newStatus);
                    repository.save(order);
                    log.info("Order {} ({}): {} -> {}", order.getBuyerRef(), order.getPoNumber(), previousStatus, newStatus);
                }
            } catch (LegacySupplyRateLimitedException e) {
                log.warn("Quota hit while polling, stopping this cycle: {}", e.getMessage());
                return;
            } catch (LegacySupplyUnavailableException e) {
                log.warn("Could not check status for {}: {}", order.getBuyerRef(), e.getMessage());
            } catch (LegacySupplyRejectedException e) {
                order.setStatus(SupplierOrderStatus.FAILED);
                repository.save(order);
                log.error("Order {} ({}) now permanently failed: {}", order.getBuyerRef(), order.getPoNumber(), e.getMessage());
            } catch (RuntimeException e) {
                log.error("Unexpected error tracking {}", order.getBuyerRef(), e);
            }
        }
    }
}