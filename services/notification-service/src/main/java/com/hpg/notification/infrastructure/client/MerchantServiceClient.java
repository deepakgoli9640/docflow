package com.hpg.notification.infrastructure.client;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.UUID;

/**
 * The one synchronous service-to-service call in the architecture: fetching a merchant's webhook signing
 * secret (docs/01-architecture-overview.md §4.2).
 *
 * <p>It is off the payment path — nothing a hospital or patient waits on depends on it — and it is
 * guarded three ways:
 * <ul>
 *   <li><b>Cache</b> with a short time to live: a burst of notifications for one merchant makes one call,
 *       and a rotated secret takes effect within the TTL.</li>
 *   <li><b>Circuit breaker</b>: when merchant-service is down, calls stop immediately instead of every
 *       due notification waiting for a timeout.</li>
 *   <li><b>Retryable failure</b>: an unavailable configuration fails the delivery attempt, and the
 *       dispatcher's backoff retries it later. Nothing is lost and nothing is sent unsigned.</li>
 * </ul>
 */
public class MerchantServiceClient {

    private static final Logger log = LoggerFactory.getLogger(MerchantServiceClient.class);

    private final RestClient http;
    private final ServiceTokenProvider tokens;
    private final CircuitBreaker circuitBreaker;
    private final Cache<UUID, MerchantWebhookConfig> cache;

    public MerchantServiceClient(RestClient http, ServiceTokenProvider tokens, CircuitBreaker circuitBreaker,
            Duration cacheTtl, int cacheSize) {
        this.http = http;
        this.tokens = tokens;
        this.circuitBreaker = circuitBreaker;
        this.cache = Caffeine.newBuilder().maximumSize(cacheSize).expireAfterWrite(cacheTtl).build();
    }

    /** @throws MerchantConfigUnavailableException when the configuration cannot be fetched right now */
    public MerchantWebhookConfig webhookConfig(UUID merchantId) {
        MerchantWebhookConfig cached = cache.getIfPresent(merchantId);
        if (cached != null) {
            return cached;
        }
        try {
            MerchantWebhookConfig fetched = circuitBreaker.executeSupplier(() -> fetch(merchantId));
            cache.put(merchantId, fetched);
            return fetched;
        } catch (CallNotPermittedException e) {
            throw new MerchantConfigUnavailableException("merchant-service circuit is open", e);
        }
    }

    /** Called after a rotation is known to have happened, so the next signature uses the new secret. */
    public void evict(UUID merchantId) {
        cache.invalidate(merchantId);
    }

    private MerchantWebhookConfig fetch(UUID merchantId) {
        try {
            return request(merchantId);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                // Our own token may have expired or been rotated: get a new one and try once more.
                log.info("Internal call was rejected ({}); refreshing the service token", e.getStatusCode());
                tokens.invalidate();
                return request(merchantId);
            }
            throw translate(e);
        }
    }

    private MerchantWebhookConfig request(UUID merchantId) {
        try {
            MerchantWebhookConfig config = http.get()
                    .uri("/internal/v1/merchants/{id}/webhook-config", merchantId)
                    .header("Authorization", "Bearer " + tokens.accessToken())
                    .retrieve()
                    .body(MerchantWebhookConfig.class);
            if (config == null) {
                throw new MerchantConfigUnavailableException("empty response from merchant-service", null);
            }
            return config;
        } catch (RestClientResponseException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MerchantConfigUnavailableException("merchant-service is unreachable", e);
        }
    }

    private static RuntimeException translate(RestClientResponseException e) {
        HttpStatusCode status = e.getStatusCode();
        if (status.value() == 404) {
            // The merchant is gone from merchant-service; retrying will not bring it back.
            return new MerchantNotFoundException("merchant-service does not know this merchant");
        }
        return new MerchantConfigUnavailableException("merchant-service returned " + status, e);
    }

    /** Retryable: the configuration could not be read right now. */
    public static class MerchantConfigUnavailableException extends RuntimeException {
        public MerchantConfigUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Permanent: there is nothing to deliver to. */
    public static class MerchantNotFoundException extends RuntimeException {
        public MerchantNotFoundException(String message) {
            super(message);
        }
    }
}
