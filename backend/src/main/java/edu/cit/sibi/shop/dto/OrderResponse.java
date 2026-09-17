package edu.cit.sibi.shop.dto;

import edu.cit.sibi.inventory.dto.InventoryItem;

import java.util.List;

/**
 * { status, reason, items: [{ productId, outcome }], inventory }
 * "inventory" is the current state of every product touched by this order,
 * after the operation completes (whether CONFIRMED or REJECTED).
 */
public record OrderResponse(
        Long orderId,
        String status,
        String reason,
        List<OrderItemResult> items,
        List<InventoryItem> inventory
) {
}
