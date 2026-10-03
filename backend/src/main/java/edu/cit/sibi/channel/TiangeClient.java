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

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Everything that actually talks to Tiangge over HTTP. Package-private on
 * purpose. Nothing outside edu.cit.sibi.channel may reference this type.
 */
@Component
class TiangeClient {

    private static final Logger log = LoggerFactory.getLogger(TiangeClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String clientId;
    private final String apiKey;
    private final String appName;
    private final ClientInstance clientInstance;
    private volatile Instant lastHeartbeatOk;
    private final Object heartbeatLock = new Object();

    TiangeClient(RestTemplate tiangeRestTemplate,
                 @Value("${tiangge.base-url}") String baseUrl,
                 @Value("${tiangge.client-id}") String clientId,
                 @Value("${tiangge.api-key}") String apiKey,
                 @Value("${tiangge.app-name:order-inventory-integration}") String appName,
                 ClientInstance clientInstance) {
        this.restTemplate = tiangeRestTemplate;
        this.baseUrl = baseUrl;
        this.clientId = clientId;
        this.apiKey = apiKey;
        this.appName = appName;
        this.clientInstance = clientInstance;
    }

    HeartbeatResponse sendHeartbeat() {
        long uptimeSeconds = Duration.between(clientInstance.startedAt(), Instant.now()).toSeconds();
        HeartbeatRequest request = new HeartbeatRequest(appName,
                DateTimeFormatter.ISO_INSTANT.format(clientInstance.startedAt()), uptimeSeconds);
        HeartbeatResponse response = exchange(HttpMethod.POST, "/instances/heartbeat", request, HeartbeatResponse.class);
        lastHeartbeatOk = Instant.now();
        clientInstance.heartbeatAccepted();
        return response;
    }

    private boolean heartbeatFresherThan(Duration limit) {
        Instant t = lastHeartbeatOk;
        return t != null && Duration.between(t, Instant.now()).compareTo(limit) < 0;
    }

    /**
     * How old our last accepted heartbeat may be when a call leaves. Deliberately well inside any
     * plausible "recent" window on Tiangge's side (the heartbeat runs every 5 s).
     */
    private static final Duration HEARTBEAT_MAX_AGE = Duration.ofSeconds(12);

    /**
     * Never call Tiangge as an instance without a recent heartbeat: refresh it first, or hold the call.
     * Checked before EVERY attempt, not once per request: retries can start many seconds later.
     */
    private void requireFreshHeartbeat() {
        if (heartbeatFresherThan(HEARTBEAT_MAX_AGE)) {
            return;
        }
        synchronized (heartbeatLock) {
            if (heartbeatFresherThan(HEARTBEAT_MAX_AGE)) {
                return;
            }
            try {
                sendHeartbeat();
            } catch (RuntimeException e) {
                log.warn("Inline heartbeat failed: {}", e.getMessage());
            }
            if (!heartbeatFresherThan(HEARTBEAT_MAX_AGE)) {
                throw new IllegalStateException("No recent heartbeat accepted by Tiangge yet; holding this call");
            }
        }
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

    /** One attempt only: StockSyncListener#sendInOrder does the retrying. */
    TiangeOrderView decide(String orderId, String decision, String shopOrderId, String reason) {
        return exchange(HttpMethod.POST, "/orders/" + orderId + "/decision",
                new DecisionRequest(decision, shopOrderId, reason), TiangeOrderView.class, 1);
    }

    /** One attempt only, same reason as decide(). */
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

    private <T> T exchange(HttpMethod method, String path, Object body, Class<T> responseType) {
        return exchange(method, path, body, responseType, 3);
    }

    private <T> T exchange(HttpMethod method, String path, Object body, Class<T> responseType, int maxAttempts) {
        boolean isHeartbeat = path.equals("/instances/heartbeat");
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (!isHeartbeat) {
                requireFreshHeartbeat();
            }
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
                // 4xx: not retryable. Log and rethrow so the caller can decide.
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