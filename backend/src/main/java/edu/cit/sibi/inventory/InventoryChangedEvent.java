package edu.cit.sibi.inventory;

/**
 * Published whenever a product's stock actually changes, for ANY reason —
 * an order reservation, a cancellation restock, or a LegacySupply delivery
 * restock. Lab 4's channel module listens for this to push the new
 * quantity to Tiangge (Task 3: "Use your Lab 2 domain events. Do not
 * publish on a timer."), so this event is deliberately not Tiangge-aware —
 * Inventory has no idea Tiangge exists, same as every other event here.
 */
public record InventoryChangedEvent(String productId, int newStock) {
}
