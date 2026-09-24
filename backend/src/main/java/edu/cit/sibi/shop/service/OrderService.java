package edu.cit.sibi.shop.service;

import edu.cit.sibi.inventory.InventoryService;
import edu.cit.sibi.inventory.dto.InventoryItem;
import edu.cit.sibi.inventory.dto.ReservationResult;
import edu.cit.sibi.shop.dto.OrderItemRequest;
import edu.cit.sibi.shop.dto.OrderItemResult;
import edu.cit.sibi.shop.dto.OrderResponse;
import edu.cit.sibi.shop.dto.OrderSummary;
import edu.cit.sibi.shop.event.LowStockEvent;
import edu.cit.sibi.shop.event.OrderPlacedEvent;
import edu.cit.sibi.shop.event.OrderRejectedEvent;
import edu.cit.sibi.shop.exception.OrderAlreadyCancelledException;
import edu.cit.sibi.shop.exception.OrderNotFoundException;
import edu.cit.sibi.shop.model.Order;
import edu.cit.sibi.shop.model.OrderItem;
import edu.cit.sibi.shop.model.OrderStatus;
import edu.cit.sibi.shop.repository.OrderRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calls InventoryService in-process (plain Java method calls, no network
 * hop) to place and cancel orders. Only ever depends on the InventoryService
 * interface — see InventoryServiceImpl for why that's enforced by the
 * compiler, not just convention.
 *
 * Also publishes domain events (OrderPlacedEvent / OrderRejectedEvent /
 * LowStockEvent) via Spring's ApplicationEventPublisher instead of calling
 * the Notification module directly. OrderService has no import from
 * edu.cit.sibi.notification anywhere in this file — Notification finds out
 * about orders purely by listening for these events.
 */
@Service
public class OrderService {

    /** Below this remaining stock, a LowStockEvent is published. */
    private static final int LOW_STOCK_THRESHOLD = 5;

    private final InventoryService inventoryService;
    private final OrderRepository orderRepository;
    private final ApplicationEventPublisher eventPublisher;

    public OrderService(InventoryService inventoryService,
                         OrderRepository orderRepository,
                         ApplicationEventPublisher eventPublisher) {
        this.inventoryService = inventoryService;
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public OrderResponse placeOrder(List<OrderItemRequest> requestedItems) {
        // Phase 1: validate every line item WITHOUT reserving anything.
        // All-or-nothing: if any single item would fail, the whole order is
        // rejected before a single unit of stock is touched.
        Map<String, ReservationResult> checks = new LinkedHashMap<>();
        boolean allOk = true;
        for (OrderItemRequest item : requestedItems) {
            ReservationResult check = inventoryService.checkAvailability(item.productId(), item.quantity());
            checks.put(item.productId(), check);
            if (!check.success()) {
                allOk = false;
            }
        }

        if (!allOk) {
            return rejectOrder(requestedItems, checks);
        }

        // Phase 2: every item passed validation — now actually reserve each
        // one. Still inside the same @Transactional method/DB transaction
        // as the order-row insert below, so a failure partway through rolls
        // the whole thing back at the database level too.
        List<OrderItemResult> itemResults = new ArrayList<>();
        List<InventoryItem> updatedInventory = new ArrayList<>();

        Order order = new Order(OrderStatus.CONFIRMED, null);
        for (OrderItemRequest item : requestedItems) {
            ReservationResult result = inventoryService.reserve(item.productId(), item.quantity());
            // result.success() is expected true here since we just validated
            // it — but if a concurrent request changed stock in between,
            // this could still fail. Not resolved in this lab; see README.
            order.addItem(new OrderItem(item.productId(), item.quantity(), "OK"));
            itemResults.add(new OrderItemResult(item.productId(), item.quantity(), "OK"));
            updatedInventory.add(result.item());

            if (result.item() != null && result.item().stock() < LOW_STOCK_THRESHOLD) {
                eventPublisher.publishEvent(
                        new LowStockEvent(result.item().productId(), result.item().stock(), LOW_STOCK_THRESHOLD));
            }
        }

        Order saved = orderRepository.save(order);
        eventPublisher.publishEvent(new OrderPlacedEvent(saved.getOrderId()));

        return new OrderResponse(saved.getOrderId(), OrderStatus.CONFIRMED.name(), null, itemResults, updatedInventory);
    }

    private OrderResponse rejectOrder(List<OrderItemRequest> requestedItems, Map<String, ReservationResult> checks) {
        List<OrderItemResult> itemResults = new ArrayList<>();
        List<InventoryItem> snapshot = new ArrayList<>();
        String firstFailureReason = null;

        Order order = new Order(OrderStatus.REJECTED, null);
        for (OrderItemRequest item : requestedItems) {
            ReservationResult check = checks.get(item.productId());
            String outcome = check.success() ? "OK" : check.reason();
            if (!check.success() && firstFailureReason == null) {
                firstFailureReason = check.reason();
            }
            order.addItem(new OrderItem(item.productId(), item.quantity(), outcome));
            itemResults.add(new OrderItemResult(item.productId(), item.quantity(), outcome));
            if (check.item() != null) {
                snapshot.add(check.item());
            }
        }

        order.setReason(firstFailureReason);
        Order saved = orderRepository.save(order);
        eventPublisher.publishEvent(new OrderRejectedEvent(saved.getOrderId(), firstFailureReason));

        return new OrderResponse(saved.getOrderId(), OrderStatus.REJECTED.name(), firstFailureReason, itemResults, snapshot);
    }

    @Transactional
    public CancelResponseHolder cancelOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new OrderAlreadyCancelledException(orderId);
        }

        List<InventoryItem> restocked = new ArrayList<>();
        // Only CONFIRMED orders actually reserved stock; a REJECTED order
        // never touched inventory, so cancelling one just flips its status.
        if (order.getStatus() == OrderStatus.CONFIRMED) {
            for (OrderItem item : order.getItems()) {
                InventoryItem updated = inventoryService.restock(item.getProductId(), item.getQuantity());
                restocked.add(updated);
            }
        }

        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);

        return new CancelResponseHolder(order.getOrderId(), OrderStatus.CANCELLED.name(), restocked);
    }

    public List<OrderSummary> getOrderHistory() {
        return orderRepository.findAll().stream()
                .map(this::toSummary)
                .toList();
    }

    private OrderSummary toSummary(Order order) {
        List<OrderItemResult> items = order.getItems().stream()
                .map(i -> new OrderItemResult(i.getProductId(), i.getQuantity(), i.getOutcome()))
                .toList();
        return new OrderSummary(order.getOrderId(), order.getStatus().name(), order.getReason(), order.getCreatedAt(), items);
    }

    /** Small holder so the controller doesn't need a separate service call for inventory. */
    public record CancelResponseHolder(Long orderId, String status, List<InventoryItem> inventory) {
    }
}
