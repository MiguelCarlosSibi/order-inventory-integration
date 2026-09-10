package edu.cit.sibi.inventory.dto;

/**
 * Outcome of an InventoryService.reserve(...) call.
 *
 * @param success whether stock was actually decremented
 * @param reason  null on success; a human-readable rejection reason otherwise
 *                (e.g. "Insufficient stock" or "Product not found")
 * @param item    the inventory state after the attempt (or the last-known
 *                state on failure); null only if the product doesn't exist
 */
public record ReservationResult(boolean success, String reason, InventoryItem item) {
}
