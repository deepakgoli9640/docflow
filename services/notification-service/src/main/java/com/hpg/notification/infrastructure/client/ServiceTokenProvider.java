package com.hpg.notification.infrastructure.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Gets and caches this service's own access token (OAuth2 client credentials) from merchant-service.
 *
 * <p>Service-to-service calls are authenticated the same way a hospital's calls are: a client id and
 * secret exchanged for a short-lived JWT. The token is cached until shortly before it expires, so a
 * burst of notifications does not fetch one token per delivery.
 *
 * <p>Spring Security's {@code OAuth2AuthorizedClientManager} does this too, but it is built around an
 * HTTP request's security context, and these calls happen on a scheduler thread where there is none.
 * A cached POST is less machinery for the same result, and it is obvious what it does.
 */
public class ServiceTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenProvider.class);
    /** Refresh this long before expiry, so a token never expires between here and the callee. */
    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(60);

    private final RestClient http;
    private final String clientId;
    private final String clientSecret;
    private final Clock clock;

    private volatile String cachedToken;
    private volatile Instant cachedUntil = Instant.EPOCH;

    public ServiceTokenProvider(RestClient http, String clientId, String clientSecret, Clock clock) {
        this.http = http;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.clock = clock;
    }

    public synchronized String accessToken() {
        if (cachedToken != null && clock.instant().isBefore(cachedUntil)) {
            return cachedToken;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        @SuppressWarnings("unchecked")
        Map<String, Object> response = http.post()
                .uri("/oauth2/token")
                .headers(headers -> headers.setBasicAuth(clientId, clientSecret, StandardCharsets.UTF_8))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
        if (response == null || response.get("access_token") == null) {
            throw new IllegalStateException("merchant-service returned no access_token");
        }
        cachedToken = String.valueOf(response.get("access_token"));
        long expiresIn = response.get("expires_in") instanceof Number number ? number.longValue() : 300;
        cachedUntil = clock.instant().plusSeconds(expiresIn).minus(EXPIRY_MARGIN);
        log.debug("Obtained a service access token, valid for {}s", expiresIn);
        return cachedToken;
    }

    /** Drops the cached token, so the next call fetches a fresh one (used after a 401). */
    public synchronized void invalidate() {
        cachedToken = null;
        cachedUntil = Instant.EPOCH;
    }
}
