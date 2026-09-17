package edu.cit.sibi.notification.listener;

import edu.cit.sibi.notification.model.Notification;
import edu.cit.sibi.notification.repository.NotificationRepository;
import edu.cit.sibi.shop.event.LowStockEvent;
import edu.cit.sibi.shop.event.OrderPlacedEvent;
import edu.cit.sibi.shop.event.OrderRejectedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The only things this class imports from outside its own package are the
 * three event record types in edu.cit.sibi.shop.event — never OrderService,
 * InventoryService, or any entity. Order and Inventory, in turn, never
 * import anything from edu.cit.sibi.notification. The event classes are the
 * entire contract in both directions.
 *
 * Plain @EventListener (not @Async): listeners run synchronously, on the
 * same thread and within the same transaction as the code that published
 * the event. Kept synchronous here on purpose — see the README reflection
 * for why.
 */
@Component
class NotificationEventListener {

    private final NotificationRepository notificationRepository;

    NotificationEventListener(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @EventListener
    public void onOrderPlaced(OrderPlacedEvent event) {
        notificationRepository.save(new Notification("Order #" + event.orderId() + " confirmed"));
    }

    @EventListener
    public void onOrderRejected(OrderRejectedEvent event) {
        String reason = event.reason() != null ? event.reason() : "unknown reason";
        notificationRepository.save(new Notification("Order #" + event.orderId() + " rejected: " + reason));
    }

    @EventListener
    public void onLowStock(LowStockEvent event) {
        String message = "Reorder needed: " + event.productId() + " has " + event.currentStock()
                + " left (threshold " + event.threshold() + ")";
        notificationRepository.save(new Notification(message));
    }
}
