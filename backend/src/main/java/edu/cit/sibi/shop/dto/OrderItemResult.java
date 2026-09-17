package edu.cit.sibi.shop.dto;

/**
 * Per-line-item outcome within an OrderResponse.
 *
 * @param outcome "OK" if this item was (or would have been) reservable;
 *                otherwise the specific rejection reason for THIS item.
 *                On a REJECTED order, an item can show "OK" here meaning it
 *                individually passed validation but was never reserved
 *                because a different item in the same order failed —
 *                that's the all-or-nothing rule in action.
 */
public record OrderItemResult(String productId, int quantity, String outcome) {
}
