package com.hpg.notification.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.hpg.notification.application.NotificationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches webhook URL + signing secret from merchant-service's internal endpoint with a client-credentials
 * token (SERVICE role). This is the one synchronous internal call in the architecture. Results are cached
 * briefly so a burst of notifications for one hospital costs one lookup; a rotated secret is picked up
 * within the cache TTL.
 */
public class MerchantServiceDirectory implements MerchantDirectory {

    private final RestClient http;
    private final NotificationProperties properties;
    private final Clock clock;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();
    private volatile Token token;

    private record Cached(WebhookConfig config, Instant expiresAt) {
    }

    private record Token(String value, Instant expiresAt) {
    }

    public MerchantServiceDirectory(NotificationProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
        factory.setReadTimeout(properties.requestTimeout());
        this.http = RestClient.builder().baseUrl(properties.merchantServiceUri()).requestFactory(factory).build();
    }

    @Override
    public WebhookConfig webhookConfig(UUID merchantId) {
        Instant now = clock.instant();
        Cached cached = cache.get(merchantId);
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.config();
        }
        JsonNode body = http.get().uri("/internal/v1/merchants/{id}/webhook-config", merchantId)
                .headers(h -> h.setBearerAuth(accessToken()))
                .retrieve().body(JsonNode.class);
        if (body == null) {
            throw new IllegalStateException("empty webhook-config response");
        }
        WebhookConfig config = new WebhookConfig(merchantId, text(body, "merchantStatus"), text(body, "webhookUrl"),
                text(body, "webhookSecret"));
        cache.put(merchantId, new Cached(config, now.plus(properties.webhookConfigCacheTtl())));
        return config;
    }

    private String accessToken() {
        Token current = token;
        Instant now = clock.instant();
        if (current != null && current.expiresAt().isAfter(now.plusSeconds(30))) {
            return current.value();
        }
        if (properties.clientSecret() == null || properties.clientSecret().isBlank()) {
            throw new IllegalStateException("HPG_NOTIFICATION_CLIENT_SECRET is not set; cannot call merchant-service");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        JsonNode response = http.post().uri("/oauth2/token")
                .headers(h -> h.setBasicAuth(properties.clientId(), properties.clientSecret(), StandardCharsets.UTF_8))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve().body(JsonNode.class);
        if (response == null || !response.hasNonNull("access_token")) {
            throw new IllegalStateException("token endpoint returned no access_token");
        }
        Token fresh = new Token(response.get("access_token").asText(),
                now.plusSeconds(response.path("expires_in").asLong(300)));
        token = fresh;
        return fresh.value();
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }
}
