package edu.cit.sibi.inventory.dto;

/**
 * Read-only snapshot of a product's inventory. The Order module only ever
 * sees this DTO, never the JPA entity — so Inventory is free to change its
 * persistence model without breaking Order.
 */
public record InventoryItem(String productId, String name, int stock) {
}
