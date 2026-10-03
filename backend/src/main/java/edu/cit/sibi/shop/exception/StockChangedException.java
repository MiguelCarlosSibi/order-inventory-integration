package edu.cit.sibi.shop.exception;

/** Stock changed between the availability check and the reservation. Caller retries. */
public class StockChangedException extends RuntimeException {
    public StockChangedException(String productId) {
        super("Stock changed concurrently for " + productId);
    }
}