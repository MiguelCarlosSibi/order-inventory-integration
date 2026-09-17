package edu.cit.sibi.shop.exception;

/** Maps to HTTP 404 in GlobalExceptionHandler. */
public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException(Long orderId) {
        super("Order not found: " + orderId);
    }
}
