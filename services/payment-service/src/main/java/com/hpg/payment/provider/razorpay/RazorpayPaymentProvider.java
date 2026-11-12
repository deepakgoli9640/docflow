package com.hpg.payment.provider.razorpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.common.money.Money;
import com.hpg.payment.domain.CaptureMode;
import com.hpg.payment.provider.CheckoutConfig;
import com.hpg.payment.provider.CheckoutRequest;
import com.hpg.payment.provider.CreateOrderCommand;
import com.hpg.payment.provider.HmacSignatures;
import com.hpg.payment.provider.PaymentProvider;
import com.hpg.payment.provider.ProviderAuthenticationException;
import com.hpg.payment.provider.ProviderOrder;
import com.hpg.payment.provider.ProviderPayment;
import com.hpg.payment.provider.ProviderPaymentStatus;
import com.hpg.payment.provider.ProviderRateLimitedException;
import com.hpg.payment.provider.ProviderRejectedException;
import com.hpg.payment.provider.ProviderTimeoutException;
import com.hpg.payment.provider.ProviderUnavailableException;
import com.hpg.payment.provider.ProviderWebhookEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Razorpay adapter: Orders API + Standard Checkout + webhooks.
 *
 * <p>Built on Spring's {@link RestClient} over the JDK HttpClient rather than the Razorpay Java SDK so
 * that timeouts, error mapping, observability and the base URL (WireMock in tests) are under our control.
 *
 * <p>Request/response shapes follow Razorpay's public API documentation. Items marked
 * <b>verify</b> must be confirmed against the sandbox before production use; with no test keys yet,
 * this adapter has been exercised only against WireMock stubs of those documented shapes.
 */
public class RazorpayPaymentProvider implements PaymentProvider {

    public static final String NAME = "RAZORPAY";
    private static final Logger log = LoggerFactory.getLogger(RazorpayPaymentProvider.class);

    private final RazorpayProperties properties;
    private final RestClient http;
    private final ObjectMapper json;

    public RazorpayPaymentProvider(RazorpayProperties properties, ObjectMapper json) {
        properties.requireCredentials();
        this.properties = properties;
        this.json = json;
        HttpClient client = HttpClient.newBuilder()
                // HTTP/1.1 explicitly: the JDK client otherwise attempts an HTTP/2 upgrade, which over plain
                // HTTP (h2c) breaks requests with bodies on some servers. One predictable protocol is worth
                // more here than HTTP/2's multiplexing at our call volume.
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
    public ProviderOrder createOrder(CreateOrderCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", RazorpayAmounts.toMinorUnits(command.amount()));
        body.put("currency", command.amount().currency());
        body.put("receipt", command.receipt());           // max 40 chars; a UUID is 36
        body.put("notes", command.notes());
        // verify: order-level capture settings ("payment.capture" = automatic | manual).
        body.put("payment", Map.of("capture", command.captureMode() == CaptureMode.AUTO ? "automatic" : "manual"));
        JsonNode order = call("createOrder", () -> http.post().uri("/v1/orders")
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class));
        return new ProviderOrder(order.path("id").asText(),
                RazorpayAmounts.fromMinorUnits(order.path("amount").asLong(), order.path("currency").asText()),
                order.path("status").asText());
    }

    @Override
    public List<ProviderPayment> fetchOrderPayments(String providerOrderId) {
        JsonNode collection = call("fetchOrderPayments", () -> http.get()
                .uri("/v1/orders/{id}/payments", providerOrderId).retrieve().body(JsonNode.class));
        List<ProviderPayment> payments = new ArrayList<>();
        for (JsonNode item : collection.path("items")) {
            payments.add(toPayment(item));
        }
        return payments;
    }

    @Override
    public ProviderPayment fetchPayment(String providerPaymentId) {
        return toPayment(call("fetchPayment", () -> http.get()
                .uri("/v1/payments/{id}", providerPaymentId).retrieve().body(JsonNode.class)));
    }

    @Override
    public ProviderPayment capture(String providerPaymentId, Money amount) {
        Map<String, Object> body = Map.of("amount", RazorpayAmounts.toMinorUnits(amount), "currency", amount.currency());
        try {
            return toPayment(call("capture", () -> http.post().uri("/v1/payments/{id}/capture", providerPaymentId)
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class)));
        } catch (ProviderRejectedException e) {
            // A capture repeated after a lost response is rejected as "already captured". Confirm by reading
            // the payment instead of matching the error text (verify exact error wording in the sandbox).
            ProviderPayment current = fetchPayment(providerPaymentId);
            if (current.isCaptured()) {
                log.info("Capture of {} already done at provider; treating as success", providerPaymentId);
                return current;
            }
            throw e;
        }
    }

    @Override
    public boolean verifyCheckoutSignature(String providerOrderId, String providerPaymentId, String signature) {
        // Razorpay: signature = hex(HMAC_SHA256(order_id + "|" + payment_id, key_secret))
        String expected = HmacSignatures.hmacSha256Hex(properties.keySecret(),
                (providerOrderId + "|" + providerPaymentId).getBytes(StandardCharsets.UTF_8));
        return HmacSignatures.matches(expected, signature);
    }

    @Override
    public boolean verifyWebhookSignature(byte[] rawBody, String signature) {
        // Razorpay: X-Razorpay-Signature = hex(HMAC_SHA256(raw request body, webhook secret))
        return HmacSignatures.matches(HmacSignatures.hmacSha256Hex(properties.webhookSecret(), rawBody), signature);
    }

    @Override
    public Optional<ProviderWebhookEvent> parseWebhook(String eventId, byte[] rawBody) {
        try {
            JsonNode root = json.readTree(rawBody);
            String event = root.path("event").asText();
            JsonNode paymentEntity = root.path("payload").path("payment").path("entity");
            Instant createdAt = root.hasNonNull("created_at") ? Instant.ofEpochSecond(root.get("created_at").asLong())
                    : null;
            if (paymentEntity.isMissingNode() || paymentEntity.isNull()) {
                return Optional.empty();
            }
            return Optional.of(new ProviderWebhookEvent(eventId, event, createdAt, toPayment(paymentEntity)));
        } catch (IOException e) {
            throw new IllegalArgumentException("Webhook body is not valid JSON", e);
        }
    }

    @Override
    public CheckoutConfig checkoutConfig(CheckoutRequest request) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("key", properties.keyId());          // public key id; the secret never reaches the browser
        options.put("order_id", request.providerOrderId());
        options.put("amount", RazorpayAmounts.toMinorUnits(request.amount()));
        options.put("currency", request.amount().currency());
        options.put("name", request.merchantName());
        options.put("description", request.description());
        Map<String, Object> prefill = new LinkedHashMap<>();
        putIfPresent(prefill, "name", request.customerName());
        putIfPresent(prefill, "email", request.customerEmail());
        putIfPresent(prefill, "contact", request.customerPhone());
        options.put("prefill", prefill);
        // verify: Standard Checkout "method" option to show only the merchant's enabled methods.
        options.put("method", methodFlags(request.enabledMethods()));
        return new CheckoutConfig(properties.checkoutScriptUrl(), options, false);
    }

    private static Map<String, Boolean> methodFlags(Set<String> enabled) {
        Map<String, Boolean> flags = new LinkedHashMap<>();
        for (String method : List.of("card", "upi", "netbanking", "wallet")) {
            flags.put(method, enabled.contains(method.toUpperCase(Locale.ROOT)));
        }
        return flags;
    }

    private static void putIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    private ProviderPayment toPayment(JsonNode p) {
        String currency = p.path("currency").asText("INR");
        return new ProviderPayment(
                p.path("id").asText(),
                p.path("order_id").isNull() ? null : p.path("order_id").asText(null),
                mapStatus(p.path("status").asText()),
                RazorpayAmounts.fromMinorUnits(p.path("amount").asLong(), currency),
                p.path("method").asText(null),
                p.path("error_code").isNull() ? null : p.path("error_code").asText(null),
                p.path("error_description").isNull() ? null : p.path("error_description").asText(null));
    }

    /** Razorpay payment statuses: created, authorized, captured, refunded, failed. */
    static ProviderPaymentStatus mapStatus(String status) {
        return switch (status) {
            case "authorized" -> ProviderPaymentStatus.AUTHORIZED;
            case "captured" -> ProviderPaymentStatus.CAPTURED;
            case "refunded" -> ProviderPaymentStatus.REFUNDED;
            case "failed" -> ProviderPaymentStatus.FAILED;
            default -> ProviderPaymentStatus.CREATED;
        };
    }

    /**
     * Executes an HTTP call and maps every failure into the provider error taxonomy. The key distinction:
     * a connect failure means the request never arrived (safe to retry), while a read timeout means it
     * may have been processed (outcome unknown).
     */
    private JsonNode call(String operation, Supplier<JsonNode> request) {
        try {
            JsonNode body = request.get();
            if (body == null) {
                throw new ProviderUnavailableException(operation, "empty response body", null);
            }
            return body;
        } catch (RestClientResponseException e) {
            throw mapHttpError(operation, e);
        } catch (ResourceAccessException e) {
            Throwable cause = e.getCause();
            if (cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException) {
                throw new ProviderUnavailableException(operation, "could not connect", e);
            }
            if (cause instanceof HttpTimeoutException) {
                throw new ProviderTimeoutException(operation, "no response within " + properties.readTimeout(), e);
            }
            // Connection reset mid-request etc.: we cannot know whether the provider acted.
            throw new ProviderTimeoutException(operation, "connection failed after the request was sent", e);
        }
    }

    private RuntimeException mapHttpError(String operation, RestClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == 401 || status == 403) {
            log.error("Razorpay rejected our credentials during {} (HTTP {})", operation, status);
            return new ProviderAuthenticationException(operation, "HTTP " + status, e);
        }
        if (status == 429) {
            return new ProviderRateLimitedException(operation, "rate limited by provider");
        }
        if (status >= 500) {
            return new ProviderUnavailableException(operation, "HTTP " + status, e);
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
        return new ProviderRejectedException(operation, code, description);
    }

    /** Header names Razorpay uses on webhooks. */
    public static final class Headers {
        public static final String SIGNATURE = "X-Razorpay-Signature";
        public static final String EVENT_ID = "X-Razorpay-Event-Id";

        private Headers() {
        }
    }
}
