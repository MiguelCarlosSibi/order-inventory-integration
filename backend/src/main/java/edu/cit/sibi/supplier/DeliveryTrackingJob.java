package edu.cit.sibi.supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

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

    /** One status pass at a time, so a delivery is never published twice. */
    private final ReentrantLock passLock = new ReentrantLock();
    private static final long REFRESH_MIN_GAP_MILLIS = 2000;
    private final Map<String, Long> lastRefresh = new ConcurrentHashMap<>();

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
        passLock.lock();
        try {
            List<SupplierOrder> open = repository.findByStatusIn(OPEN_STATUSES);
            if (open.isEmpty()) {
                checkedSinceStart = true;
                return;
            }
            log.info("Polling {} open supplier order(s)", open.size());
            if (pollOrders(open)) {
                checkedSinceStart = true;
            }
        } finally {
            passLock.unlock();
        }
    }

    /**
     * Checks the open purchase orders of ONE product right now (our saved status can lag LegacySupply
     * by a whole poll interval). At most once per product every couple of seconds, and it never waits
     * long for a pass that is already running - that pass is doing the same work.
     */
    void refresh(String productId) {
        long now = System.currentTimeMillis();
        Long last = lastRefresh.get(productId);
        if (last != null && now - last < REFRESH_MIN_GAP_MILLIS) {
            return;
        }
        boolean locked = false;
        try {
            locked = passLock.tryLock(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!locked) {
            return;
        }
        try {
            lastRefresh.put(productId, System.currentTimeMillis());
            List<SupplierOrder> open = repository.findByStatusIn(OPEN_STATUSES).stream()
                    .filter(o -> o.getProductId().equals(productId)).toList();
            if (!open.isEmpty()) {
                pollOrders(open);
            }
        } finally {
            passLock.unlock();
        }
    }

    /** Must be called holding passLock. Returns true if every order could be checked. */
    private boolean pollOrders(List<SupplierOrder> open) {
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
        return allChecked;
    }
}