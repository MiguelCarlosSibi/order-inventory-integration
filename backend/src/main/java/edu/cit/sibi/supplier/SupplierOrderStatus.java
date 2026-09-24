package edu.cit.sibi.supplier;

/**
 * Our own status vocabulary for a reorder, translated from (and never
 * exposing) LegacySupply's {@code StatusCode} integers. The mapping lives
 * entirely inside {@code LegacySupplyClient} / {@code SupplierGatewayImpl}.
 *
 * <pre>
 *   LegacySupply StatusCode   Our status
 *   ------------------------  ----------
 *   (not yet sent / offline)  PENDING
 *   10 Accepted                ACCEPTED
 *   20 Picking                 PICKING
 *   30 Shipped                 SHIPPED
 *   40 Delivered                DELIVERED
 *   (unrecognized code)        FAILED  — see INTEGRATION.md for how we
 *                                        decided to handle unexpected codes
 * </pre>
 */
public enum SupplierOrderStatus {
    PENDING,
    ACCEPTED,
    PICKING,
    SHIPPED,
    DELIVERED,
    FAILED
}
