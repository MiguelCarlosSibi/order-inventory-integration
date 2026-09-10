package edu.cit.sibi.shop.service;

import edu.cit.sibi.inventory.InventoryService;
import edu.cit.sibi.inventory.dto.InventoryItem;
import edu.cit.sibi.inventory.dto.ReservationResult;
import edu.cit.sibi.shop.dto.OrderResponse;
import edu.cit.sibi.shop.model.Order;
import edu.cit.sibi.shop.model.OrderStatus;
import edu.cit.sibi.shop.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Calls InventoryService in-process (a plain Java method call, no network
 * hop) to place an order. Notice the constructor only ever mentions the
 * InventoryService interface — it has no way to reference
 * InventoryServiceImpl even if it wanted to, because that class isn't
 * visible outside its own package.
 */
@Service
public class OrderService {

    private final InventoryService inventoryService;
    private final OrderRepository orderRepository;

    public OrderService(InventoryService inventoryService, OrderRepository orderRepository) {
        this.inventoryService = inventoryService;
        this.orderRepository = orderRepository;
    }

    @Transactional
    public OrderResponse placeOrder(String productId, int quantity) {
        ReservationResult result = inventoryService.reserve(productId, quantity);

        OrderStatus status = result.success() ? OrderStatus.CONFIRMED : OrderStatus.REJECTED;
        Order order = new Order(productId, quantity, status, result.reason());
        orderRepository.save(order);

        InventoryItem inventorySnapshot = result.item() != null ? result.item() : lookupSafely(productId);
        return new OrderResponse(status.name(), result.reason(), inventorySnapshot);
    }

    private InventoryItem lookupSafely(String productId) {
        try {
            return inventoryService.getItem(productId);
        } catch (Exception e) {
            return null;
        }
    }
}
