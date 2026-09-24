package edu.cit.sibi.supplier;

/**
 * Thrown for anything that looks transient: connect/read timeout,
 * {@code E-SYS-50} (processing error), {@code E-SYS-99} (service
 * unavailable), or a bare connection failure. {@link RetryExecutor} catches
 * exactly this type to decide whether to retry.
 * <p>
 * {@code E-RATE-03} (quota exceeded) is intentionally NOT treated as this —
 * retrying immediately into a quota rejection just burns more quota. See
 * {@code LegacySupplyClient} for how that one is handled instead.
 */
class LegacySupplyUnavailableException extends RuntimeException {
    LegacySupplyUnavailableException(String message) {
        super(message);
    }

    LegacySupplyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
