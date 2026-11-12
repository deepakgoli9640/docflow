package com.hpg.payment.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.hpg.payment.provider.HmacSignatures;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

/**
 * WireMock stand-in for Razorpay's REST API, using its documented JSON shapes. A SIMULATION of
 * Razorpay used only by tests.
 */
public final class RazorpayStub {

    public static final String KEY_SECRET = "test_key_secret";
    public static final String WEBHOOK_SECRET = "test_webhook_secret";

    private final WireMockServer server;

    public RazorpayStub(WireMockServer server) {
        this.server = server;
    }

    /** Like the real API, every call returns a new order id (WireMock response templating). */
    public void orderCreated(long paise) {
        server.stubFor(post("/v1/orders").willReturn(okJson("""
                {"id":"order_{{randomValue length=14 type='ALPHANUMERIC'}}","entity":"order","amount":%d,
                 "currency":"INR","status":"created"}""".formatted(paise))));
    }

    public void orderCreationFails(int status) {
        server.stubFor(post("/v1/orders").willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"rejected by stub\"}}")));
    }

    public void orderPayments(String orderId, String... paymentJson) {
        server.stubFor(get(urlEqualTo("/v1/orders/" + orderId + "/payments")).willReturn(okJson(
                "{\"entity\":\"collection\",\"count\":" + paymentJson.length + ",\"items\":["
                        + Arrays.stream(paymentJson).collect(Collectors.joining(",")) + "]}")));
    }

    public void paymentLookup(String paymentId, String paymentJson) {
        server.stubFor(get(urlEqualTo("/v1/payments/" + paymentId)).willReturn(okJson(paymentJson)));
    }

    public void paymentLookupFails(String paymentId) {
        server.stubFor(get(urlEqualTo("/v1/payments/" + paymentId)).willReturn(aResponse().withStatus(500)));
    }

    public void capture(String paymentId, MappingBuilderCustomizer customizer, String responseJson) {
        MappingBuilder mapping = post(urlEqualTo("/v1/payments/" + paymentId + "/capture"));
        server.stubFor(customizer.customize(mapping, responseJson));
    }

    @FunctionalInterface
    public interface MappingBuilderCustomizer {
        MappingBuilder customize(MappingBuilder mapping, String responseJson);
    }

    public static String payment(String id, String orderId, String status, long paise) {
        return """
                {"id":"%s","entity":"payment","amount":%d,"currency":"INR","status":"%s","order_id":"%s",
                 "method":"upi","error_code":%s,"error_description":%s}""".formatted(id, paise, status, orderId,
                "failed".equals(status) ? "\"BAD_REQUEST_ERROR\"" : "null",
                "failed".equals(status) ? "\"Payment declined by bank\"" : "null");
    }

    public static String webhook(String event, String paymentJson, long createdAtEpochSeconds) {
        return """
                {"entity":"event","account_id":"acc_test","event":"%s","contains":["payment"],
                 "payload":{"payment":{"entity":%s}},"created_at":%d}""".formatted(event, paymentJson, createdAtEpochSeconds);
    }

    public static String webhookSignature(String body) {
        return HmacSignatures.hmacSha256Hex(WEBHOOK_SECRET, body.getBytes(StandardCharsets.UTF_8));
    }

    public static String checkoutSignature(String orderId, String paymentId) {
        return HmacSignatures.hmacSha256Hex(KEY_SECRET, (orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8));
    }
}
