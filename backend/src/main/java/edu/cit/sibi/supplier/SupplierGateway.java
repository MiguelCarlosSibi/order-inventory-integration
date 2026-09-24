package edu.cit.sibi.supplier;

/**
 * Anti-Corruption Layer entry point for placing replenishment orders with
 * the external LegacySupply system.
 * <p>
 * This is one of only two public types in this module (the other is
 * {@link SupplierOrderStatus}, plus the {@link ReorderResult} return type
 * and the {@link SupplierOrderDeliveredEvent} that Inventory listens for).
 * Callers work only in their own terms — product id, units needed — and
 * get back a result expressed the same way. Nothing here describes
 * LegacySupply's XML shapes, SupplierSku values, PackSize/Uom conversions,
 * or StatusCode integers; all of that stays package-private inside this
 * module (see {@link SupplierGatewayImpl}, {@code LegacySupplyClient}, and
 * the {@code edu.cit.sibi.supplier.xml} sub-package).
 */
public interface SupplierGateway {

    /**
     * Places (or, if LegacySupply can't be reached right now, queues for
     * later retry) a reorder for the given product.
     *
     * @param productId   our own inventory product id
     * @param unitsNeeded how many individual units we want restocked —
     *                    the gateway converts this to LegacySupply's unit
     *                    of measure internally, rounding up
     * @return the outcome of the attempt; {@link SupplierOrderStatus#PENDING}
     *         means LegacySupply was unreachable and the reorder has been
     *         persisted for {@code PendingReorderRetryJob} to resend later —
     *         it is never silently dropped
     */
    ReorderResult requestReorder(String productId, int unitsNeeded);
}
