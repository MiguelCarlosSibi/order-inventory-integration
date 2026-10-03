package edu.cit.sibi.supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Package-private on purpose: nothing outside edu.cit.sibi.supplier can
 * import this or call anything not on the SupplierGateway interface.
 * This is where the ACL's translation happens: our (productId,
 * unitsNeeded) terms in, LegacySupply's (SupplierSku, Qty-in-cases,
 * BuyerRef) terms out, and the reverse on the way back.
 */
@Service
class SupplierGatewayImpl implements SupplierGateway {

    private static final Logger log = LoggerFactory.getLogger(SupplierGatewayImpl.class);

    private final LegacySupplyClient client;
    private final SupplierOrderRepository repository;
    private final ProductCatalogMapping catalogMapping;
    private final DeliveryTrackingJob deliveryTracking;

    SupplierGatewayImpl(LegacySupplyClient client, SupplierOrderRepository repository,
                        ProductCatalogMapping catalogMapping, DeliveryTrackingJob deliveryTracking) {
        this.client = client;
        this.repository = repository;
        this.catalogMapping = catalogMapping;
        this.deliveryTracking = deliveryTracking;
    }

    @Override
    @Transactional
    public ReorderResult requestReorder(String productId, int unitsNeeded) {
        int cases = catalogMapping.unitsToCases(productId, unitsNeeded);

        // Persist BEFORE calling out to LegacySupply, so the reorder is
        // durable: if the process dies mid-call, the PENDING row (with its
        // requestId already fixed) is resent later by PendingReorderRetryJob.
        SupplierOrder order = new SupplierOrder(productId, cases, unitsNeeded);
        order = repository.save(order); // assigns the id

        order.setBuyerRef("RO-" + order.getId());
        order = repository.save(order);

        return attemptSend(order, productId);
    }

    /** Called both from requestReorder() and from PendingReorderRetryJob for a previously-queued order. */
    ReorderResult attemptSend(SupplierOrder order, String productId) {
        String supplierSku = catalogMapping.supplierSkuFor(productId)
                .orElseThrow(() -> new IllegalStateException("No SupplierSku mapping for " + productId));

        try {
            PurchaseOrderAckXml ack = client.placeOrder(supplierSku, order.getCases(), order.getBuyerRef(), order.getRequestId());
            order.setPoNumber(ack.poNumber);
            order.setStatus(mapStatus(ack.statusCode));
            repository.save(order);
            log.info("Reorder {} accepted by LegacySupply as {}", order.getBuyerRef(), ack.poNumber);
        } catch (LegacySupplyRejectedException e) {
            // Permanent: a human needs to look at the mapping/config.
            order.setStatus(SupplierOrderStatus.FAILED);
            repository.save(order);
            log.error("Reorder {} permanently rejected: {}", order.getBuyerRef(), e.getMessage());
        } catch (LegacySupplyUnavailableException e) {
            // Transient: leave it PENDING, PendingReorderRetryJob will try again.
            log.warn("Reorder {} could not be sent yet (LegacySupply unavailable), left PENDING: {}", order.getBuyerRef(), e.getMessage());
        }

        return new ReorderResult(order.getId(), order.getBuyerRef(), order.getPoNumber(), order.getStatus());
    }

    @Override
    public Optional<String> supplierSkuFor(String productId) {
        return catalogMapping.supplierSkuFor(productId);
    }

    private static final List<SupplierOrderStatus> OPEN_STATUSES =
            List.of(SupplierOrderStatus.PENDING, SupplierOrderStatus.ACCEPTED,
                    SupplierOrderStatus.PICKING, SupplierOrderStatus.SHIPPED);

    private static final List<SupplierOrderStatus> ON_THE_WAY =
            List.of(SupplierOrderStatus.ACCEPTED, SupplierOrderStatus.PICKING, SupplierOrderStatus.SHIPPED);

    @Override
    public boolean hasOpenReorder(String productId) {
        return repository.existsByProductIdAndStatusIn(productId, OPEN_STATUSES);
    }

    @Override
    public boolean deliveriesUpToDate() {
        return deliveryTracking.checkedSinceStart();
    }

    @Override
    public boolean hasRestockOnTheWay(String productId) {
        return repository.existsByProductIdAndStatusIn(productId, ON_THE_WAY);
    }

    @Override
    public synchronized boolean ensureRestock(String productId, int minUnits) {
        if (!repository.existsByProductIdAndStatusIn(productId, OPEN_STATUSES)) {
            requestReorder(productId, minUnits);
        } else if (!hasRestockOnTheWay(productId)) {
            // A reorder is still PENDING (LegacySupply was unreachable): send it now
            // instead of waiting for the retry job. Same request id, so it is safe to repeat.
            repository.findByStatus(SupplierOrderStatus.PENDING).stream()
                    .filter(o -> o.getProductId().equals(productId))
                    .forEach(o -> attemptSend(o, productId));
        }
        return hasRestockOnTheWay(productId);
    }

    static SupplierOrderStatus mapStatus(int legacySupplyStatusCode) {
        return switch (legacySupplyStatusCode) {
            case 10 -> SupplierOrderStatus.ACCEPTED;
            case 20 -> SupplierOrderStatus.PICKING;
            case 30 -> SupplierOrderStatus.SHIPPED;
            case 40 -> SupplierOrderStatus.DELIVERED;
            default -> {
                log.warn("Unrecognized LegacySupply StatusCode {} - see INTEGRATION.md for how this is handled", legacySupplyStatusCode);
                yield SupplierOrderStatus.FAILED;
            }
        };
    }
}