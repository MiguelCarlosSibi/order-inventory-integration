package edu.cit.sibi.shop.event;

/**
 * Published after any successful reserve() that leaves a product's stock
 * below LOW_STOCK_THRESHOLD. Distinct from OrderPlacedEvent so Notification
 * can log it as its own kind of entry ("reorder needed") rather than folding
 * it into the order-confirmation message.
 */
public record LowStockEvent(String productId, int currentStock, int threshold) {
}
