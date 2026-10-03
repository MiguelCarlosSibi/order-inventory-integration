package edu.cit.sibi.channel;

import edu.cit.sibi.common.ClientInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * Everything that actually talks to Tiangge over HTTP. Package-private on
 * purpose — same rule as LegacySupplyClient in edu.cit.sibi.supplier.
 * Nothing outside edu.cit.sibi.channel may reference this type.
 */
@Component
class TiangeClient {

    private static final Logger log = LoggerFactory.getLogger(TiangeClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String clientId;
    private final String apiKey;
    private final ClientInstance clientInstance;

    TiangeClient(RestTemplate tiangeRestTemplate,
                 @Value("${tiangge.base-url}") String baseUrl,
                 @Value("${tiangge.client-id}") String clientId,
                 @Value("${tiangge.api-key}") String apiKey,
                 ClientInstance clientInstance) {
        this.restTemplate = tiangeRestTemplate;
        this.baseUrl = baseUrl;
        this.clientId = clientId;
        this.apiKey = apiKey;
        this.clientInstance = clientInstance;
    }

    HeartbeatResponse sendHeartbeat(HeartbeatRequest request) {
        return exchange(HttpMethod.POST, "/instances/heartbeat", request, HeartbeatResponse.class);
    }

    void publishListings(List<ListingDto> listings) {
        exchange(HttpMethod.PUT, "/listings", listings, Void.class);
    }

    void publishStock(List<StockDto> stock) {
        // One attempt only: StockSyncListener rebuilds the figures from live data on every try,
        // so a retry can never re-send an old number.
        exchange(HttpMethod.PUT, "/stock", stock, Void.class, 1);
    }

    FeedResponse fetchFeed(long after, int limit) {
        String path = "/feed?after=" + after + "&limit=" + limit;
        return exchange(HttpMethod.GET, path, null, FeedResponse.class);
    }

    TiangeOrderView decide(String orderId, String decision, String shopOrderId, String reason) {
        return exchange(HttpMethod.POST, "/orders/" + orderId + "/decision",
                new DecisionRequest(decision, shopOrderId, reason), TiangeOrderView.class, 1);
    }

    TiangeOrderView resolve(String orderId, String status) {
        return exchange(HttpMethod.POST, "/orders/" + orderId + "/resolution",
                new ResolutionRequest(status), TiangeOrderView.class, 1);
    }

    TiangeOrderView confirmCancellation(String orderId) {
        return exchange(HttpMethod.POST, "/orders/" + orderId + "/cancellation",
                new CancellationConfirmRequest(true), TiangeOrderView.class);
    }

    TiangeOrderView lookupOrder(String orderId) {
        return exchange(HttpMethod.GET, "/orders/" + orderId, null, TiangeOrderView.class);
    }

    /**
     * One call, with a short retry for exactly the failures the manual calls safe to retry
     * (timeouts, 503 unavailable). 4xx errors are NOT retried: decision_conflict, not_backordered
     * and not_cancelled are terminal outcomes the caller needs to see, not transient failures.
     */
    private <T> T exchange(HttpMethod method, String path, Object body, Class<T> responseType) {
        return exchange(method, path, body, responseType, 3);
    }

    private <T> T exchange(HttpMethod method, String path, Object body, Class<T> responseType, int maxAttempts) {
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpEntity<Object> entity = new HttpEntity<>(body, jsonHeaders());
                return restTemplate.exchange(baseUrl + path, method, entity, responseType).getBody();
            } catch (HttpServerErrorException | ResourceAccessException e) {
                lastError = e;
                log.warn("Tiangge call {} {} failed (attempt {}/{}): {}", method, path, attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) {
                    sleep(attempt);
                }
            } catch (HttpClientErrorException e) {
                // 4xx: not retryable. Log and rethrow so the caller can decide
                // (e.g. treat decision_conflict as "already handled").
                log.warn("Tiangge call {} {} rejected: {}", method, path, e.getResponseBodyAsString());
                throw e;
            }
        }
        throw lastError;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Client-Id", clientId);
        headers.set("Authorization", "Bearer " + apiKey);
        headers.set("X-Client-Instance", clientInstance.id());
        return headers;
    }

    private void sleep(int attempt) {
        try {
            Thread.sleep(400L * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}