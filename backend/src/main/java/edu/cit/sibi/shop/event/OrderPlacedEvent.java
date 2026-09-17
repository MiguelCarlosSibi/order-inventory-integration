package edu.cit.sibi.shop.event;

/**
 * Published by OrderService via Spring's ApplicationEventPublisher when an
 * order is CONFIRMED. This is the entire contract the Notification module
 * is allowed to depend on — it never imports OrderService, InventoryService,
 * or any entity from either module, only this event class.
 */
public record OrderPlacedEvent(Long orderId) {
}
