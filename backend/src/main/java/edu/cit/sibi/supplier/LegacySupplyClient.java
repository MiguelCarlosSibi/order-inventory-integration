package edu.cit.sibi.supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the raw HTTP conversation with LegacySupply: authentication,
 * session refresh, XML (de)serialization, and translating its error
 * vocabulary into either a retryable {@link LegacySupplyUnavailableException}
 * or a permanent {@link LegacySupplyRejectedException}. Nothing above this
 * class (SupplierGatewayImpl and up) ever sees an XML DTO, a SupplierSku,
 * a PackSize/Uom, or a raw StatusCode.
 */
@Component
class LegacySupplyClient {

    private static final Logger log = LoggerFactory.getLogger(LegacySupplyClient.class);

    private final RestTemplate restTemplate;
    private final RetryExecutor retryExecutor = new RetryExecutor();
    private final String baseUrl;
    private final String clientId;
    private final String apiKey;

    // Session state. TODO(Part B): once you've measured how long a session
    // actually lasts, consider proactively refreshing a bit before that
    // point instead of only reacting to E-AUTH-07 - document the measured
    // duration in INTEGRATION.md either way.
    private final AtomicReference<String> sessionToken = new AtomicReference<>();
    private volatile Instant sessionIssuedAt;

    LegacySupplyClient(RestTemplate supplierRestTemplate,
                        @Value("${legacysupply.base-url}") String baseUrl,
                        @Value("${legacysupply.client-id}") String clientId,
                        @Value("${legacysupply.api-key}") String apiKey) {
        this.restTemplate = supplierRestTemplate;
        this.baseUrl = baseUrl;
        this.clientId = clientId;
        this.apiKey = apiKey;
    }

    /** GET /ping — needs no session, used for a basic reachability check. */
    boolean ping() {
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(baseUrl + "/ping", String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Places a purchase order. {@code requestId} becomes the X-Request-Id
     * header — LegacySupply will not process a request repeated with the
     * same id twice, which is our primary duplicate-order guard on top of
     * the BuyerRef uniqueness check the gateway does before calling this.
     */
    PurchaseOrderAckXml placeOrder(String supplierSku, int qty, String buyerRef, String requestId) {
        return withSession(token -> {
            PurchaseOrderRequestXml body = new PurchaseOrderRequestXml(supplierSku, qty, buyerRef);
            HttpHeaders headers = xmlHeaders(token);
            headers.set("X-Request-Id", requestId);
            HttpEntity<PurchaseOrderRequestXml> entity = new HttpEntity<>(body, headers);

            try {
                ResponseEntity<PurchaseOrderAckXml> response = restTemplate.exchange(
                        baseUrl + "/purchase-orders", HttpMethod.POST, entity, PurchaseOrderAckXml.class);
                return response.getBody();
            } catch (HttpClientErrorException e) {
                throw translateClientError(e);
            } catch (HttpServerErrorException | ResourceAccessException e) {
                throw new LegacySupplyUnavailableException("placeOrder failed: " + e.getMessage(), e);
            }
        });
    }

    /** GET /purchase-orders/{PoNumber} — used by the delivery-tracking job. */
    PurchaseOrderStatusXml getStatus(String poNumber) {
        return withSession(token -> {
            HttpEntity<Void> entity = new HttpEntity<>(xmlHeaders(token));
            try {
                ResponseEntity<PurchaseOrderStatusXml> response = restTemplate.exchange(
                        baseUrl + "/purchase-orders/" + poNumber, HttpMethod.GET, entity, PurchaseOrderStatusXml.class);
                return response.getBody();
            } catch (HttpClientErrorException e) {
                throw translateClientError(e);
            } catch (HttpServerErrorException | ResourceAccessException e) {
                throw new LegacySupplyUnavailableException("getStatus failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * GET /purchase-orders?buyerRef={BuyerRef} — used to check whether a
     * PENDING reorder was actually accepted before resending it (belt and
     * suspenders alongside X-Request-Id, per Part D).
     */
    PurchaseOrderListXml findByBuyerRef(String buyerRef) {
        return withSession(token -> {
            HttpEntity<Void> entity = new HttpEntity<>(xmlHeaders(token));
            try {
                ResponseEntity<PurchaseOrderListXml> response = restTemplate.exchange(
                        baseUrl + "/purchase-orders?buyerRef=" + buyerRef, HttpMethod.GET, entity, PurchaseOrderListXml.class);
                return response.getBody();
            } catch (HttpClientErrorException e) {
                throw translateClientError(e);
            } catch (HttpServerErrorException | ResourceAccessException e) {
                throw new LegacySupplyUnavailableException("findByBuyerRef failed: " + e.getMessage(), e);
            }
        });
    }

    /** GET /catalog — used during Part B discovery and to (re)build ProductCatalogMapping's values by hand. */
    CatalogXml getCatalog() {
        return withSession(token -> {
            HttpEntity<Void> entity = new HttpEntity<>(xmlHeaders(token));
            try {
                ResponseEntity<CatalogXml> response = restTemplate.exchange(
                        baseUrl + "/catalog", HttpMethod.GET, entity, CatalogXml.class);
                return response.getBody();
            } catch (HttpClientErrorException e) {
                throw translateClientError(e);
            } catch (HttpServerErrorException | ResourceAccessException e) {
                throw new LegacySupplyUnavailableException("getCatalog failed: " + e.getMessage(), e);
            }
        });
    }

    // --- session handling -------------------------------------------------

    private interface SessionCall<T> {
        T call(String sessionToken);
    }

    /**
     * Runs {@code call} with a valid session, retrying (via RetryExecutor)
     * on transient failures, and transparently re-authenticating exactly
     * once if the session turns out to be no longer accepted
     * (E-AUTH-02/03/07) partway through.
     */
    private <T> T withSession(SessionCall<T> call) {
        return retryExecutor.withRetry("legacysupply-call", () -> {
            ensureSession();
            try {
                return call.call(sessionToken.get());
            } catch (LegacySupplySessionExpiredException e) {
                log.info("Session no longer accepted, re-authenticating and retrying once");
                sessionToken.set(null);
                ensureSession();
                try {
                    return call.call(sessionToken.get());
                } catch (LegacySupplySessionExpiredException again) {
                    throw new LegacySupplyUnavailableException("Session rejected even after re-login: " + again.getMessage());
                }
            }
        });
    }

    private void ensureSession() {
        if (sessionToken.get() != null) {
            return;
        }
        synchronized (this) {
            if (sessionToken.get() != null) {
                return;
            }
            AuthRequestXml authRequest = new AuthRequestXml(clientId, apiKey);
            HttpEntity<AuthRequestXml> entity = new HttpEntity<>(authRequest, xmlHeaders(null));
            try {
                ResponseEntity<AuthResponseXml> response = restTemplate.exchange(
                        baseUrl + "/auth/token", HttpMethod.POST, entity, AuthResponseXml.class);
                AuthResponseXml body = response.getBody();
                if (body == null || body.sessionToken == null) {
                    throw new LegacySupplyUnavailableException("Auth succeeded but returned no session token");
                }
                sessionToken.set(body.sessionToken);
                sessionIssuedAt = Instant.now();
                log.info("Obtained new LegacySupply session");
            } catch (HttpClientErrorException e) {
                // E-AUTH-01 (credentials rejected) is not transient - retrying
                // with the same bad key just wastes attempts.
                throw new LegacySupplyRejectedException("Authentication failed: " + errorBody(e), e);
            } catch (HttpServerErrorException | ResourceAccessException e) {
                throw new LegacySupplyUnavailableException("Authentication failed: " + e.getMessage(), e);
            }
        }
    }

    // --- error translation --------------------------------------------------

    private RuntimeException translateClientError(HttpClientErrorException e) {
        String body = errorBody(e);
        HttpStatusCode status = e.getStatusCode();

        if (status.value() == 401) {
            // E-AUTH-02 / E-AUTH-03 / E-AUTH-07 - session no longer accepted.
            return new LegacySupplySessionExpiredException(body);
        }
        if (status.value() == 429) {
            return new LegacySupplyRateLimitedException("Quota exceeded: " + body, e);
        }
        if (status.value() == 503) {
            return new LegacySupplyUnavailableException("LegacySupply unavailable: " + body, e);
        }
        // 400/404/415/422 - our request was wrong or the resource doesn't
        // exist. Permanent, not retryable.
        return new LegacySupplyRejectedException(body, e);
    }

    private String errorBody(HttpClientErrorException e) {
        try {
            LsErrorXml error = new com.fasterxml.jackson.dataformat.xml.XmlMapper()
                    .readValue(e.getResponseBodyAsString(), LsErrorXml.class);
            return error.toString();
        } catch (Exception parseFailure) {
            return e.getStatusCode() + " " + e.getResponseBodyAsString();
        }
    }

    private HttpHeaders xmlHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_XML));
        if (token != null) {
            headers.set("X-LS-Session", token);
        }
        return headers;
    }

    /** Thrown internally when a session is no longer accepted; caught by withSession() to trigger one re-auth + retry. */
    private static class LegacySupplySessionExpiredException extends RuntimeException {
        LegacySupplySessionExpiredException(String message) {
            super(message);
        }
    }
}
