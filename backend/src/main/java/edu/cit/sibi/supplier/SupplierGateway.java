package edu.cit.sibi.supplier;

import java.util.Optional;

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

    /**
     * The LegacySupply SupplierSku we restock the given product from, per
     * our Lab 3 catalog mapping — needed by Lab 4's Tiangge listings
     * publisher (Task 2: "Each listing names the LegacySupply SupplierSku
     * you restock it from"). Empty if we have no mapping for this product.
     */
    Optional<String> supplierSkuFor(String productId);

    /**
     * True if this product has a reorder already placed with LegacySupply
     * that has not yet been delivered or permanently failed (PENDING,
     * ACCEPTED, PICKING, or SHIPPED). Used by Lab 4's order-decision logic
     * to decide BACKORDERED (restock already on its way) vs REJECTED
     * (nothing coming) when current stock can't cover a Tiangge order.
     */
    boolean hasOpenReorder(String productId);
    /** True only if LegacySupply has actually accepted a purchase order that hasn't been delivered or failed yet. */
    boolean hasRestockOnTheWay(String productId);

    /**
     * Places a reorder of at least minUnits unless one is already open.
     * @return true if a real purchase order is now on its way
     */
    boolean ensureRestock(String productId, int minUnits);

    /**
     * True once our purchase-order statuses have been refreshed from LegacySupply since the app
     * started. Right after a restart the saved statuses can be stale, and deciding an order
     * against a stale "restock on the way" would be wrong.
     */
    boolean deliveriesUpToDate();

    /** Units that purchase orders LegacySupply has already accepted (not yet delivered) will bring in. */
    int inboundUnits(String productId);

    /**
     * Makes sure open purchase orders will bring in at least minInboundUnits in total, placing an
     * extra reorder for the difference if they will not.
     * @return true if a real purchase order is now on its way
     */
    boolean ensureSupply(String productId, int minInboundUnits);

    /**
     * Re-checks, right now, the purchase orders still on their way for this product, so a delivery
     * that already happened at LegacySupply is restocked before anyone relies on a stale "on the way".
     * Rate-limited per product and never throws: a failed check just leaves the saved statuses as they are.
     */
    void refreshInbound(String productId);
}
