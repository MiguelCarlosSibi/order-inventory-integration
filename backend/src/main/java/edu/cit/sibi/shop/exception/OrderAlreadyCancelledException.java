package edu.cit.sibi.shop.exception;

/** Maps to HTTP 409 in GlobalExceptionHandler. */
public class OrderAlreadyCancelledException extends RuntimeException {
    public OrderAlreadyCancelledException(Long orderId) {
        super("Order " + orderId + " is already cancelled");
    }
}
