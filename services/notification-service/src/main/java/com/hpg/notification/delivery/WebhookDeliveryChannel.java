package com.hpg.notification.delivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.notification.domain.Channel;
import com.hpg.notification.domain.MerchantNotificationReplica;
import com.hpg.notification.domain.Notification;
import com.hpg.notification.infrastructure.client.MerchantServiceClient;
import com.hpg.notification.infrastructure.client.MerchantWebhookConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Delivers a signed callback to the hospital's system (docs/08-api-contracts.md §8).
 *
 * <p>The hospital has to be able to trust what arrives, so every request carries
 * {@code X-HPG-Signature: v1=<hex HMAC-SHA256 of "<timestamp>.<body>">} using that merchant's secret.
 * The timestamp is inside the signed material: without it, anyone who captured one request could replay
 * it for ever, and the hospital would have no way to tell.
 *
 * <p>Each request also carries an event id (so the hospital can ignore a duplicate) and a sequence number
 * (so it can ignore an update older than one it has already applied). Both matter because delivery is at
 * least once and, across retries, not necessarily in order.
 */
public class WebhookDeliveryChannel implements DeliveryChannel {

    public static final String HEADER_EVENT_ID = "X-HPG-Event-Id";
    public static final String HEADER_EVENT_TYPE = "X-HPG-Event-Type";
    public static final String HEADER_TIMESTAMP = "X-HPG-Timestamp";
    public static final String HEADER_SIGNATURE = "X-HPG-Signature";
    public static final String HEADER_SEQUENCE = "X-HPG-Sequence";
    public static final String SIGNATURE_VERSION = "v1";

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryChannel.class);

    private final MerchantServiceClient merchants;
    private final PrivateNetworkGuard guard;
    private final ObjectMapper json;
    private final RestClient http;
    private final Clock clock;

    public WebhookDeliveryChannel(MerchantServiceClient merchants, PrivateNetworkGuard guard, ObjectMapper json,
            Duration connectTimeout, Duration readTimeout, Clock clock) {
        this.merchants = merchants;
        this.guard = guard;
        this.json = json;
        this.clock = clock;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                // Never follow redirects: a redirect is a way to steer a signed request at another host.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
        factory.setReadTimeout(readTimeout);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public Channel channel() {
        return Channel.WEBHOOK;
    }

    @Override
    public DeliveryResult deliver(Notification notification, MerchantNotificationReplica merchant) {
        String url = notification.getRecipient();
        if (url == null || url.isBlank()) {
            return DeliveryResult.permanent("no webhook URL configured", null);
        }
        MerchantWebhookConfig config;
        try {
            config = merchants.webhookConfig(notification.getMerchantId());
        } catch (MerchantServiceClient.MerchantNotFoundException e) {
            return DeliveryResult.permanent(e.getMessage(), null);
        } catch (MerchantServiceClient.MerchantConfigUnavailableException e) {
            // Retryable on purpose: an unsigned or unsent webhook is better than a wrongly signed one.
            return DeliveryResult.retryable("cannot read the signing secret: " + e.getMessage(), null);
        }
        if (config.webhookSecret() == null || config.webhookSecret().isBlank()) {
            return DeliveryResult.permanent("merchant has no webhook signing secret", null);
        }

        URI uri;
        try {
            uri = URI.create(url);
            guard.check(uri);
        } catch (PrivateNetworkGuard.BlockedTargetException | IllegalArgumentException e) {
            log.warn("Refusing to deliver notification {} : {}", notification.getId(), e.getMessage());
            return DeliveryResult.permanent(e.getMessage(), null);
        } catch (PrivateNetworkGuard.UnresolvableTargetException e) {
            return DeliveryResult.retryable(e.getMessage(), null);
        }

        String body = body(notification);
        long timestamp = clock.instant().getEpochSecond();
        String signature = SIGNATURE_VERSION + "=" + sign(config.webhookSecret(), timestamp + "." + body);
        try {
            ResponseEntity<Void> response = http.post().uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HEADER_EVENT_ID, notification.getSourceEventId().toString())
                    .header(HEADER_EVENT_TYPE, webhookEventType(notification.getEventType()))
                    .header(HEADER_TIMESTAMP, String.valueOf(timestamp))
                    .header(HEADER_SIGNATURE, signature)
                    .header(HEADER_SEQUENCE, String.valueOf(notification.getSequenceNo()))
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            int status = response.getStatusCode().value();
            if (!response.getStatusCode().is2xxSuccessful()) {
                // Redirects land here, because the client is told never to follow them: a 3xx means the
                // hospital did not process the callback, and following it would let a hospital (or whoever
                // controls its DNS) point our signed request at a host of their choosing.
                return DeliveryResult.retryable("endpoint answered " + status + " instead of accepting the callback",
                        status);
            }
            return DeliveryResult.delivered(status);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 410) {
                // The hospital says this endpoint is gone: stop calling it and let someone fix the config.
                return DeliveryResult.permanent("endpoint returned 410 Gone", status);
            }
            // Everything else is retried, including 4xx: a misconfigured or mid-deploy endpoint often
            // answers 401 or 404 for a while, and giving up on the first one would lose the notification.
            return DeliveryResult.retryable("endpoint returned " + status, status);
        } catch (RuntimeException e) {
            return DeliveryResult.retryable(e.getClass().getSimpleName() + ": " + e.getMessage(), null);
        }
    }

    /** {@code PaymentCaptured -> payment.captured}: the hospital's API contract, not our internal name. */
    static String webhookEventType(String eventType) {
        if (eventType.startsWith("Payment")) {
            return "payment." + eventType.substring("Payment".length()).toLowerCase(java.util.Locale.ROOT);
        }
        if (eventType.startsWith("Refund")) {
            return "refund." + eventType.substring("Refund".length()).toLowerCase(java.util.Locale.ROOT);
        }
        return eventType.toLowerCase(java.util.Locale.ROOT);
    }

    private String body(Notification notification) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", notification.getSourceEventId());
        payload.put("type", webhookEventType(notification.getEventType()));
        payload.put("occurredAt", notification.getCreatedAt());
        payload.put("sequence", notification.getSequenceNo());
        payload.put("data", hospitalView(notification));
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise webhook body", e);
        }
    }

    /**
     * What the hospital is told. Patient contact details are left out: the hospital gave them to us and
     * already has them, so sending them back would spread personal data for no reason.
     */
    private Map<String, Object> hospitalView(Notification notification) {
        Map<String, Object> data = new LinkedHashMap<>();
        var payload = notification.getPayload();
        data.put("paymentId", payload.path("paymentId").asText(null));
        data.put("merchantReference", payload.path("merchantReference").asText(null));
        data.put("status", payload.path("status").asText(null));
        data.put("amount", json.convertValue(payload.path("amount"), Map.class));
        if (payload.hasNonNull("providerPaymentId")) {
            data.put("providerPaymentId", payload.get("providerPaymentId").asText());
        }
        if (payload.hasNonNull("failureCode")) {
            data.put("failureCode", payload.get("failureCode").asText());
            data.put("failureReason", payload.path("failureReason").asText(null));
        }
        if (payload.path("lateCapture").asBoolean(false)) {
            data.put("lateCapture", true);
        }
        return data;
    }

    /**
     * The signature a receiver must recompute: {@code HMAC_SHA256("<timestamp>.<raw body>", secret)}, hex
     * encoded. Public because it is the contract documented to hospitals, and because tests verify it the
     * same way a hospital would.
     */
    public static String sign(String secret, String material) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(material.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
