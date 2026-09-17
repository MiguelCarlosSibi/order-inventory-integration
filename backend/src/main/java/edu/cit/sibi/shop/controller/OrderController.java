package edu.cit.sibi.shop.controller;

import edu.cit.sibi.shop.dto.CancelResponse;
import edu.cit.sibi.shop.dto.OrderRequest;
import edu.cit.sibi.shop.dto.OrderResponse;
import edu.cit.sibi.shop.dto.OrderSummary;
import edu.cit.sibi.shop.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> placeOrder(@Valid @RequestBody OrderRequest request) {
        OrderResponse response = orderService.placeOrder(request.items());
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public List<OrderSummary> getOrderHistory() {
        return orderService.getOrderHistory();
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<CancelResponse> cancelOrder(@PathVariable Long orderId) {
        OrderService.CancelResponseHolder result = orderService.cancelOrder(orderId);
        return ResponseEntity.ok(new CancelResponse(result.orderId(), result.status(), result.inventory()));
    }
}
