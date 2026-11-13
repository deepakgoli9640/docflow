package com.hpg.refund.provider.razorpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.common.money.Money;
import com.hpg.refund.provider.HmacSignatures;
import com.hpg.refund.provider.ProviderRefund;
import com.hpg.refund.provider.ProviderRejectedException;
import com.hpg.refund.provider.ProviderTimeoutException;
import com.hpg.refund.provider.ProviderUnavailableException;
import com.hpg.refund.provider.RefundProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Razorpay Refunds API adapter (docs/03-refund-lifecycle.md §6).
 *
 * <p>Every refund carries our refund id in {@code receipt} and {@code notes.refund_id}, so after a timeout
 * the recovery job can find out whether Razorpay created it before sending it again.
 * Shapes follow Razorpay's public documentation; exercised against WireMock stubs only until test keys
 * are available (<b>verify</b> in the sandbox: refund statuses and webhook payloads).
 */
public class RazorpayRefundProvider implements RefundProvider {

    public static final String NAME = "RAZORPAY";
    private static final Logger log = LoggerFactory.getLogger(RazorpayRefundProvider.class);

    private final RazorpayProperties properties;
    private final RestClient http;
    private final ObjectMapper json;

    public RazorpayRefundProvider(RazorpayProperties properties, ObjectMapper json) {
        properties.requireCredentials();
        this.properties = properties;
        this.json = json;
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(properties.readTimeout());
        this.http = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .defaultHeaders(h -> h.setBasicAuth(properties.keyId(), properties.keySecret(), StandardCharsets.UTF_8))
                .build();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ProviderRefund createRefund(String providerPaymentId, Money amount, UUID refundId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", toMinorUnits(amount));
        body.put("speed", "normal");
        body.put("receipt", refundId.toString());
        body.put("notes", Map.of("refund_id", refundId.toString()));
        return toRefund(call("createRefund", () -> http.post().uri("/v1/payments/{id}/refund", providerPaymentId)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class)));
    }

    @Override
    public List<ProviderRefund> fetchRefunds(String providerPaymentId) {
        JsonNode collection = call("fetchRefunds", () -> http.get()
                .uri("/v1/payments/{id}/refunds?count=100", providerPaymentId).retrieve().body(JsonNode.class));
        List<ProviderRefund> refunds = new ArrayList<>();
        for (JsonNode item : collection.path("items")) {
            refunds.add(toRefund(item));
        }
        return refunds;
    }

    @Override
    public ProviderRefund fetchRefund(String providerPaymentId, String providerRefundId) {
        return toRefund(call("fetchRefund", () -> http.get()
                .uri("/v1/payments/{pid}/refunds/{rid}", providerPaymentId, providerRefundId)
                .retrieve().body(JsonNode.class)));
    }

    @Override
    public boolean verifyWebhookSignature(byte[] rawBody, String signature) {
        return HmacSignatures.matches(HmacSignatures.hmacSha256Hex(properties.webhookSecret(), rawBody), signature);
    }

    @Override
    public Optional<ProviderRefund> parseWebhook(byte[] rawBody) {
        try {
            JsonNode refund = json.readTree(rawBody).path("payload").path("refund").path("entity");
            return refund.isMissingNode() || refund.isNull() ? Optional.empty() : Optional.of(toRefund(refund));
        } catch (IOException e) {
            throw new IllegalArgumentException("Webhook body is not valid JSON", e);
        }
    }

    static long toMinorUnits(Money amount) {
        return amount.amount().movePointRight(2).longValueExact();
    }

    private static ProviderRefund toRefund(JsonNode r) {
        String currency = r.path("currency").asText("INR");
        String ourId = r.path("notes").path("refund_id").asText(null);
        if (ourId == null && r.hasNonNull("receipt")) {
            ourId = r.get("receipt").asText();
        }
        return new ProviderRefund(r.path("id").asText(), r.path("payment_id").asText(null),
                new Money(BigDecimal.valueOf(r.path("amount").asLong()).movePointLeft(2), currency),
                mapStatus(r.path("status").asText()), ourId, null, null);
    }

    /** Razorpay refund statuses: pending, processed, failed. */
    static ProviderRefund.Status mapStatus(String status) {
        return switch (status) {
            case "processed" -> ProviderRefund.Status.PROCESSED;
            case "failed" -> ProviderRefund.Status.FAILED;
            default -> ProviderRefund.Status.PENDING;
        };
    }

    private JsonNode call(String operation, Supplier<JsonNode> request) {
        try {
            JsonNode body = request.get();
            if (body == null) {
                throw new ProviderUnavailableException(operation, "empty response body", null);
            }
            return body;
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status >= 500 || status == 429) {
                throw new ProviderUnavailableException(operation, "HTTP " + status, e);
            }
            if (status == 401 || status == 403) {
                log.error("Razorpay rejected our credentials during {} (HTTP {})", operation, status);
            }
            String code = "HTTP_" + status;
            String description = e.getStatusText();
            try {
                JsonNode error = json.readTree(e.getResponseBodyAsByteArray()).path("error");
                code = error.path("code").asText(code);
                description = error.path("description").asText(description);
            } catch (IOException | RuntimeException parseFailure) {
                log.debug("Unparseable Razorpay error body for {}", operation);
            }
            throw new ProviderRejectedException(operation, code, description);
        } catch (ResourceAccessException e) {
            Throwable cause = e.getCause();
            if (cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException) {
                throw new ProviderUnavailableException(operation, "could not connect", e);
            }
            if (cause instanceof HttpTimeoutException) {
                throw new ProviderTimeoutException(operation, "no response within " + properties.readTimeout(), e);
            }
            throw new ProviderTimeoutException(operation, "connection failed after the request was sent", e);
        }
    }

    /** Header names Razorpay uses on webhooks. */
    public static final class Headers {
        public static final String SIGNATURE = "X-Razorpay-Signature";
        public static final String EVENT_ID = "X-Razorpay-Event-Id";

        private Headers() {
        }
    }
}
