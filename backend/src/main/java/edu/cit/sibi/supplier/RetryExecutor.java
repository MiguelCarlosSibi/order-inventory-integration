package edu.cit.sibi.supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Small retry-with-backoff wrapper used by {@link LegacySupplyClient} for
 * every outbound call. Deliberately NOT a general-purpose library — this is
 * scoped exactly to what Part D asks for: at most 3 attempts, a short
 * timeout per attempt (enforced by RestTemplate's connect/read timeouts,
 * configured in {@link SupplierRestTemplateConfig}, not here), and backoff
 * between attempts.
 * <p>
 * Retries only on things that look transient (I/O failures, 503s via
 * {@link LegacySupplyUnavailableException}). A 4xx from LegacySupply (bad
 * SKU, invalid qty, auth rejected) is never retried — retrying a request
 * that was wrong the first time just wastes attempts and quota.
 */
class RetryExecutor {

    private static final Logger log = LoggerFactory.getLogger(RetryExecutor.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MILLIS = 400;

    <T> T withRetry(String operationName, Supplier<T> call) {
        RuntimeException lastFailure = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return call.get();
            } catch (LegacySupplyUnavailableException e) {
                lastFailure = e;
                log.warn("{} attempt {}/{} failed transiently: {}", operationName, attempt, MAX_ATTEMPTS, e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    sleep(BASE_BACKOFF_MILLIS * (1L << (attempt - 1))); // 400ms, 800ms
                }
            }
        }

        throw lastFailure;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
