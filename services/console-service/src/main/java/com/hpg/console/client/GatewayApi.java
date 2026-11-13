package com.hpg.console.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.console.client.view.ApiKeyView;
import com.hpg.console.client.view.ConfigurationView;
import com.hpg.console.client.view.MerchantView;
import com.hpg.console.client.view.NotificationDetailsView;
import com.hpg.console.client.view.NotificationView;
import com.hpg.console.client.view.PageView;
import com.hpg.console.client.view.PaymentView;
import com.hpg.console.security.ConsoleAuthentication;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every call the console makes, in one place.
 *
 * <p>It goes through the <b>gateway</b>, not straight to the services, so what the console sees is what
 * a hospital's own system would see: the same routes, the same authentication, the same rate limits. A
 * page that works here proves the public API works.
 *
 * <p>The bearer token comes from the signed-in session, so the console can never do more than the
 * person using it is allowed to do — authorisation stays with the services that own the data.
 */
public class GatewayApi {

    private final RestClient http;
    private final ObjectMapper objectMapper;

    public GatewayApi(RestClient http, ObjectMapper objectMapper) {
        this.http = http;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- merchant (hospital) views

    public MerchantView me() {
        return get("/api/v1/merchants/me", MerchantView.class);
    }

    public ConfigurationView configuration() {
        return get("/api/v1/merchants/me/configuration", ConfigurationView.class);
    }

    public ConfigurationView replaceConfiguration(Map<String, Object> body) {
        return put("/api/v1/merchants/me/configuration", body, ConfigurationView.class);
    }

    public MerchantView updateProfile(Map<String, Object> body) {
        return patch("/api/v1/merchants/me", body, MerchantView.class);
    }

    public List<ApiKeyView> apiKeys() {
        return getList("/api/v1/merchants/me/api-keys", new ParameterizedTypeReference<List<ApiKeyView>>() { });
    }

    /** The only moment the secret exists outside the hospital's own records. */
    public Map<String, Object> createApiKey() {
        return post("/api/v1/merchants/me/api-keys", null, null, MAP);
    }

    public void revokeApiKey(String keyId) {
        delete("/api/v1/merchants/me/api-keys/" + keyId);
    }

    public Map<String, Object> rotateWebhookSecret() {
        return post("/api/v1/merchants/me/webhook-secret/rotate", null, null, MAP);
    }

    // ---------------------------------------------------------------- payments

    public PageView<PaymentView> payments(Map<String, String> filters, int page, int size) {
        return getPage(uri("/api/v1/payments", filters, page, size, "createdAt,desc"), PaymentView.class);
    }

    public PaymentView payment(String paymentId) {
        return get("/api/v1/payments/" + paymentId, PaymentView.class);
    }

    public PaymentView createPayment(Map<String, Object> body, String idempotencyKey) {
        return post("/api/v1/payments", body, idempotencyKey, PaymentView.class);
    }

    public PaymentView capture(String paymentId, String idempotencyKey) {
        return post("/api/v1/payments/" + paymentId + "/capture", null, idempotencyKey, PaymentView.class);
    }

    public PaymentView cancel(String paymentId, String reason, String idempotencyKey) {
        return post("/api/v1/payments/" + paymentId + "/cancel", Map.of("reason", reason), idempotencyKey,
                PaymentView.class);
    }

    // ---------------------------------------------------------------- operations

    public PageView<MerchantView> merchants(String status, int page, int size) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (status != null && !status.isBlank()) {
            filters.put("status", status);
        }
        return getPage(uri("/api/v1/admin/merchants", filters, page, size, "createdAt,desc"), MerchantView.class);
    }

    public MerchantView merchant(String merchantId) {
        return get("/api/v1/admin/merchants/" + merchantId, MerchantView.class);
    }

    public MerchantView changeMerchantStatus(String merchantId, String action, String reason) {
        return post("/api/v1/admin/merchants/" + merchantId + "/" + action, Map.of("reason", reason), null,
                MerchantView.class);
    }

    public PageView<NotificationView> notifications(Map<String, String> filters, int page, int size) {
        return getPage(uri("/api/v1/admin/notifications", filters, page, size, "createdAt,desc"),
                NotificationView.class);
    }

    public NotificationDetailsView notification(String notificationId) {
        return get("/api/v1/admin/notifications/" + notificationId, NotificationDetailsView.class);
    }

    public NotificationView requeueNotification(String notificationId) {
        return post("/api/v1/admin/notifications/" + notificationId + "/requeue", null, null, NotificationView.class);
    }

    // ---------------------------------------------------------------- plumbing

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() { };

    private static String uri(String path, Map<String, String> filters, int page, int size, String sort) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        filters.forEach((key, value) -> {
            if (value != null && !value.isBlank()) {
                params.add(key, value);
            }
        });
        params.add("page", String.valueOf(page));
        params.add("size", String.valueOf(size));
        params.add("sort", sort);
        return UriComponentsBuilder.fromPath(path).queryParams(params).build().toUriString();
    }

    private <T> T get(String path, Class<T> type) {
        return execute(() -> http.get().uri(path).header("Authorization", bearer()).retrieve().body(type));
    }

    private <T> List<T> getList(String path, ParameterizedTypeReference<List<T>> type) {
        List<T> body = execute(() -> http.get().uri(path).header("Authorization", bearer()).retrieve().body(type));
        return body == null ? List.of() : body;
    }

    private <T> PageView<T> getPage(String path, Class<T> elementType) {
        JsonNode node = execute(() -> http.get().uri(path).header("Authorization", bearer()).retrieve()
                .body(JsonNode.class));
        List<T> content = new ArrayList<>();
        if (node != null) {
            for (JsonNode element : node.path("content")) {
                content.add(objectMapper.convertValue(element, elementType));
            }
            PageView.PageMeta meta = objectMapper.convertValue(node.path("page"), PageView.PageMeta.class);
            return new PageView<>(content, meta);
        }
        return new PageView<>(content, new PageView.PageMeta(0, 0, 0, 0));
    }

    private <T> T put(String path, Object body, Class<T> type) {
        return execute(() -> http.put().uri(path).header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(type));
    }

    private <T> T patch(String path, Object body, Class<T> type) {
        return execute(() -> http.patch().uri(path).header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(type));
    }

    private <T> T post(String path, Object body, String idempotencyKey, Class<T> type) {
        return execute(() -> requestWithBody(path, body, idempotencyKey).retrieve().body(type));
    }

    private <T> T post(String path, Object body, String idempotencyKey, ParameterizedTypeReference<T> type) {
        return execute(() -> requestWithBody(path, body, idempotencyKey).retrieve().body(type));
    }

    private RestClient.RequestBodySpec requestWithBody(String path, Object body, String idempotencyKey) {
        RestClient.RequestBodySpec request = http.post().uri(path).header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            // Every state-changing call carries one, so a double-clicked button cannot create two payments.
            request.header("Idempotency-Key", idempotencyKey);
        }
        if (body != null) {
            request.body(body);
        } else {
            request.body("{}");
        }
        return request;
    }

    private void delete(String path) {
        execute(() -> http.delete().uri(path).header("Authorization", bearer()).retrieve().toBodilessEntity());
    }

    private String bearer() {
        if (SecurityContextHolder.getContext().getAuthentication()
                instanceof ConsoleAuthentication authentication) {
            return "Bearer " + authentication.credentials().accessToken();
        }
        throw new IllegalStateException("No signed-in session to call the API with");
    }

    /** Turns an API error response into an {@link ApiException} carrying the service's own message. */
    private <T> T execute(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientResponseException e) {
            throw new ApiException(toProblem(e));
        }
    }

    private ApiException.Problem toProblem(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        try {
            JsonNode body = objectMapper.readTree(e.getResponseBodyAsByteArray());
            List<ApiException.Problem.Violation> violations = new ArrayList<>();
            for (JsonNode violation : body.path("violations")) {
                violations.add(new ApiException.Problem.Violation(violation.path("field").asText(null),
                        violation.path("message").asText(null)));
            }
            Map<String, Object> properties = new LinkedHashMap<>();
            body.properties().forEach(entry -> {
                if (!List.of("type", "title", "status", "detail", "instance", "errorCode", "correlationId",
                        "timestamp", "violations").contains(entry.getKey())) {
                    properties.put(entry.getKey(), objectMapper.convertValue(entry.getValue(), Object.class));
                }
            });
            String detail = body.path("detail").asText(null);
            return new ApiException.Problem(status, body.path("errorCode").asText("UNEXPECTED"),
                    detail == null ? ApiException.Problem.unexpected(status).detail() : detail,
                    violations, properties);
        } catch (IOException | RuntimeException parseFailure) {
            return ApiException.Problem.unexpected(status);
        }
    }

    /** A fresh idempotency key for one button press. */
    public static String newIdempotencyKey() {
        return UUID.randomUUID().toString();
    }
}
