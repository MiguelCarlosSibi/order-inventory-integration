package edu.cit.sibi.supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

class RetryExecutor {

    private static final Logger log = LoggerFactory.getLogger(RetryExecutor.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MILLIS = 400;

    <T> T withRetry(String operationName, Supplier<T> call) {
        RuntimeException lastFailure = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return call.get();
            } catch (LegacySupplyRateLimitedException e) {
                // Quota exceeded: retrying now only makes it worse. Give up; the scheduled job tries later.
                log.warn("{} hit the request quota, not retrying now: {}", operationName, e.getMessage());
                throw e;
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