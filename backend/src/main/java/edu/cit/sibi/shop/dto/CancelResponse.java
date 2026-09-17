package edu.cit.sibi.shop.dto;

import edu.cit.sibi.inventory.dto.InventoryItem;

import java.util.List;

public record CancelResponse(
        Long orderId,
        String status,
        List<InventoryItem> inventory
) {
}
