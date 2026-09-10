package edu.cit.sibi.shop.dto;

import edu.cit.sibi.inventory.dto.InventoryItem;

/**
 * { status, reason, inventory } as specified. "inventory" reuses the
 * Inventory module's own DTO rather than duplicating fields here — Order
 * depends on that DTO type the same way it depends on InventoryService.
 */
public record OrderResponse(String status, String reason, InventoryItem inventory) {
}
