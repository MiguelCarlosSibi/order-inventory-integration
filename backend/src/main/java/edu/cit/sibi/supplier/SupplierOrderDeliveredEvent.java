package edu.cit.sibi.supplier;

/**
 * Published by {@code DeliveryTrackingJob} when a tracked purchase order's
 * status flips to {@link SupplierOrderStatus#DELIVERED}.
 * <p>
 * This is the entire contract the Inventory module is allowed to depend on
 * for delivery tracking — same pattern as {@code OrderPlacedEvent} in
 * {@code edu.cit.sibi.shop.event}: Inventory imports this event class only,
 * never {@code SupplierGatewayImpl}, the XML DTOs, or anything else in this
 * package. Fields are expressed in our own terms (product id, units) — no
 * SupplierSku, PackSize, or LegacySupply status code appears here.
 */
public record SupplierOrderDeliveredEvent(String productId, int units, Long supplierOrderId) {
}
