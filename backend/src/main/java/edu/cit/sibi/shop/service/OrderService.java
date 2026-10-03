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
import edu.cit.sibi.shop.exception.StockChangedException;
import edu.cit.sibi.shop.model.Order;
import edu.cit.sibi.shop.model.OrderItem;
import edu.cit.sibi.shop.model.OrderStatus;
import edu.cit.sibi.shop.repository.OrderRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Order logic. Knows nothing about any sales channel: orders from the React
 * UI and from external channels go through exactly the same methods.
 */
@Service
public class OrderService {

    /** Below this remaining stock, a LowStockEvent is published. */
    private static final int LOW_STOCK_THRESHOLD = 5;

    private final InventoryService inventoryService;
    private final OrderRepository orderRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate tx;

    public OrderService(InventoryService inventoryService,
                        OrderRepository orderRepository,
                        ApplicationEventPublisher eventPublisher,
                        TransactionTemplate tx) {
        this.inventoryService = inventoryService;
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
        this.tx = tx;
    }

    /** Used by the React UI: no restock is assumed, so a shortage means REJECTED. */
    public OrderResponse placeOrder(List<OrderItemRequest> items) {
        return placeOrder(items, productId -> false);
    }

    /**
     * restockComing says whether a product already has a restock on its way.
     * If every short product does, the order becomes BACKORDERED, not REJECTED.
     */
    public OrderResponse placeOrder(List<OrderItemRequest> items, Predicate<String> restockComing) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // The caller owns the transaction and its retry; join it.
            return doPlaceOrder(items, restockComing);
        }
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(status -> doPlaceOrder(items, restockComing));
            } catch (StockChangedException e) {
                // Stock moved between check and reserve; the transaction rolled back.
                // Retrying re-checks stock, so it ends in a proper accept or reject.
                if (attempt >= 3) {
                    throw e;
                }
            }
        }
    }

    private OrderResponse doPlaceOrder(List<OrderItemRequest> requestedItems, Predicate<String> restockComing) {
        Map<String, ReservationResult> checks = new LinkedHashMap<>();
        boolean allOk = true;
        boolean canBackorder = true;
        for (OrderItemRequest item : requestedItems) {
            ReservationResult check = inventoryService.checkAvailability(item.productId(), item.quantity());
            checks.put(item.productId(), check);
            if (!check.success()) {
                allOk = false;
                boolean knownProduct = check.item() != null;
                if (!knownProduct || !restockComing.test(item.productId())) {
                    canBackorder = false;
                }
            }
        }

        if (!allOk) {
            return canBackorder ? backorderOrder(requestedItems) : rejectOrder(requestedItems, checks);
        }

        List<OrderItemResult> itemResults = new ArrayList<>();
        List<InventoryItem> updatedInventory = new ArrayList<>();
        Order order = new Order(OrderStatus.CONFIRMED, null);
        for (OrderItemRequest item : requestedItems) {
            ReservationResult result = inventoryService.reserve(item.productId(), item.quantity());
            if (!result.success()) {
                // Rolls back every reserve made so far in this order.
                throw new StockChangedException(item.productId());
            }
            order.addItem(new OrderItem(item.productId(), item.quantity(), "OK"));
            itemResults.add(new OrderItemResult(item.productId(), item.quantity(), "OK"));
            updatedInventory.add(result.item());

            if (result.item().stock() < LOW_STOCK_THRESHOLD) {
                eventPublisher.publishEvent(
                        new LowStockEvent(result.item().productId(), result.item().stock(), LOW_STOCK_THRESHOLD));
            }
        }

        Order saved = orderRepository.save(order);
        eventPublisher.publishEvent(new OrderPlacedEvent(saved.getOrderId()));
        return new OrderResponse(saved.getOrderId(), OrderStatus.CONFIRMED.name(), null, itemResults, updatedInventory);
    }

    private OrderResponse backorderOrder(List<OrderItemRequest> items) {
        Order order = new Order(OrderStatus.BACKORDERED, "Waiting for restock");
        List<OrderItemResult> results = new ArrayList<>();
        for (OrderItemRequest item : items) {
            order.addItem(new OrderItem(item.productId(), item.quantity(), "BACKORDERED"));
            results.add(new OrderItemResult(item.productId(), item.quantity(), "BACKORDERED"));
        }
        Order saved = orderRepository.save(order);
        return new OrderResponse(saved.getOrderId(), OrderStatus.BACKORDERED.name(),
                "Waiting for restock", results, List.of());
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

    /** Backordered orders waiting for stock, oldest first. */
    public List<Long> backorderedOrderIds() {
        return orderRepository.findByStatusOrderByCreatedAtAsc(OrderStatus.BACKORDERED)
                .stream().map(Order::getOrderId).toList();
    }

    /**
     * Tries to fill a backorder. Returns the order's status afterward:
     * CONFIRMED if it was filled, BACKORDERED if stock is still short.
     */
    @Transactional
    public OrderStatus fulfilBackorder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        if (order.getStatus() != OrderStatus.BACKORDERED) {
            return order.getStatus();
        }

        for (OrderItem item : order.getItems()) {
            if (!inventoryService.checkAvailability(item.getProductId(), item.getQuantity()).success()) {
                return OrderStatus.BACKORDERED;
            }
        }
        for (OrderItem item : order.getItems()) {
            if (!inventoryService.reserve(item.getProductId(), item.getQuantity()).success()) {
                throw new StockChangedException(item.getProductId());
            }
        }
        order.setStatus(OrderStatus.CONFIRMED);
        order.setReason(null);
        orderRepository.save(order);
        eventPublisher.publishEvent(new OrderPlacedEvent(order.getOrderId()));
        return OrderStatus.CONFIRMED;
    }
    /** Products on this order that currently lack enough stock. */
    @Transactional(readOnly = true)
    public List<String> shortProducts(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        List<String> out = new ArrayList<>();
        for (OrderItem item : order.getItems()) {
            if (!inventoryService.checkAvailability(item.getProductId(), item.getQuantity()).success()) {
                out.add(item.getProductId());
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Integer> requiredUnits(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        Map<String, Integer> out = new LinkedHashMap<>();
        for (OrderItem item : order.getItems()) {
            out.merge(item.getProductId(), item.getQuantity(), Integer::sum);
        }
        return out;
    }
    /**
     * One transaction: every restock and the CANCELLED status commit together. Without it each
     * restock committed on its own while the order still read CONFIRMED, so a stock figure computed
     * in that gap counted the units twice (real stock + "pending" units of the same order).
     */
    @Transactional
    public CancelResponseHolder cancelOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new OrderAlreadyCancelledException(orderId);
        }

        List<InventoryItem> restocked = new ArrayList<>();
        // Only CONFIRMED orders actually reserved stock. REJECTED and
        // BACKORDERED orders never did, so cancelling them restocks nothing.
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