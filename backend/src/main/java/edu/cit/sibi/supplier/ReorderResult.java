package edu.cit.sibi.supplier;

/**
 * Outcome of a {@link SupplierGateway#requestReorder} call, expressed
 * entirely in our own terms.
 *
 * @param supplierOrderId our internal {@code supplier_orders} row id
 * @param buyerRef        our own reference sent as LegacySupply's BuyerRef
 *                         (format: {@code "RO-" + supplierOrderId}) — unique
 *                         per reorder
 * @param poNumber        LegacySupply's PoNumber once accepted; {@code null}
 *                         while the order is still {@link SupplierOrderStatus#PENDING}
 * @param status          our own status, never a raw LegacySupply StatusCode
 */
public record ReorderResult(
        Long supplierOrderId,
        String buyerRef,
        String poNumber,
        SupplierOrderStatus status
) {
}
