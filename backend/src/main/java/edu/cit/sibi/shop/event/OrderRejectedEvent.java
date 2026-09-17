package edu.cit.sibi.shop.event;

/** Published when an order is REJECTED (no items were reserved). */
public record OrderRejectedEvent(Long orderId, String reason) {
}
