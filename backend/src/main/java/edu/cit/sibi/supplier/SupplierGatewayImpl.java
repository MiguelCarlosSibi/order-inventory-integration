package edu.cit.sibi.supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class SupplierGatewayImpl implements SupplierGateway {

    private static final Logger log = LoggerFactory.getLogger(SupplierGatewayImpl.class);

    private final LegacySupplyClient client;
    private final SupplierOrderRepository repository;
    private final ProductCatalogMapping catalogMapping;

    SupplierGatewayImpl(LegacySupplyClient client, SupplierOrderRepository repository, ProductCatalogMapping catalogMapping) {
        this.client = client;
        this.repository = repository;
        this.catalogMapping = catalogMapping;
    }

    @Override
    public ReorderResult requestReorder(String productId, int unitsNeeded) {
        int cases = catalogMapping.unitsToCases(productId, unitsNeeded);

        // No @Transactional on purpose: each save() commits immediately, so the
        // PENDING row (with its fixed requestId) exists before we call out.
        SupplierOrder order = new SupplierOrder(productId, cases, unitsNeeded);
        order = repository.save(order);

        order.setBuyerRef("RO-" + order.getId());
        order = repository.save(order);

        return attemptSend(order, productId);
    }

    /** Called from requestReorder() and from PendingReorderRetryJob. */
    ReorderResult attemptSend(SupplierOrder order, String productId) {
        try {
            String supplierSku = catalogMapping.supplierSkuFor(productId)
                    .orElseThrow(() -> new IllegalStateException("No SupplierSku mapping for " + productId));

            PurchaseOrderAckXml ack = client.placeOrder(supplierSku, order.getCases(), order.getBuyerRef(), order.getRequestId());
            if (ack == null || ack.poNumber == null) {
                throw new LegacySupplyUnavailableException("Empty acknowledgement from LegacySupply");
            }
            order.setPoNumber(ack.poNumber);
            order.setStatus(mapStatus(ack.statusCode));
            repository.save(order);
            log.info("Reorder {} accepted by LegacySupply as {}", order.getBuyerRef(), ack.poNumber);
        } catch (LegacySupplyRejectedException e) {
            order.setStatus(SupplierOrderStatus.FAILED);
            repository.save(order);
            log.error("Reorder {} permanently rejected: {}", order.getBuyerRef(), e.getMessage());
        } catch (LegacySupplyUnavailableException e) {
            log.warn("Reorder {} could not be sent yet, left PENDING: {}", order.getBuyerRef(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("Reorder {} left PENDING after unexpected error", order.getBuyerRef(), e);
        }

        return new ReorderResult(order.getId(), order.getBuyerRef(), order.getPoNumber(), order.getStatus());
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