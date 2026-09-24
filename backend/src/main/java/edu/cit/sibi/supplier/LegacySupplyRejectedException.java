package edu.cit.sibi.supplier;

/**
 * Thrown for a permanent rejection: bad credentials (E-AUTH-01), a
 * malformed/unrecognized SKU or quantity (E-SKU-02, E-QTY-11), an invalid
 * BuyerRef (E-REF-05), or a request-id reused with different content
 * (E-IDEM-04). Never retried by {@link RetryExecutor} — retrying an
 * identical bad request just gets the identical rejection again.
 */
class LegacySupplyRejectedException extends RuntimeException {
    LegacySupplyRejectedException(String message) {
        super(message);
    }

    LegacySupplyRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
