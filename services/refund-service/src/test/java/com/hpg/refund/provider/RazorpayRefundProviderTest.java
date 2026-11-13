package com.hpg.refund.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.hpg.common.money.Money;
import com.hpg.refund.provider.razorpay.RazorpayProperties;
import com.hpg.refund.provider.razorpay.RazorpayRefundProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Adapter against WireMock stubs of Razorpay's documented refund API (a SIMULATION of Razorpay). */
class RazorpayRefundProviderTest {

    WireMockServer server;
    RazorpayRefundProvider provider;

    @BeforeEach
    void start() {
        server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        provider = new RazorpayRefundProvider(new RazorpayProperties(server.baseUrl(), "rzp_test_x", "secret",
                "whsecret", Duration.ofMillis(500), Duration.ofMillis(500)), new ObjectMapper());
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    static String refundJson(String id, String status, long paise, String ourId) {
        return """
                {"id":"%s","entity":"refund","amount":%d,"currency":"INR","payment_id":"pay_1",
                 "notes":{"refund_id":"%s"},"receipt":"%s","status":"%s"}""".formatted(id, paise, ourId, ourId, status);
    }

    @Test
    void createsRefundWithOurIdInReceiptAndNotes() {
        UUID refundId = UUID.randomUUID();
        server.stubFor(post("/v1/payments/pay_1/refund").willReturn(okJson(refundJson("rfnd_1", "pending", 50000,
                refundId.toString()))));

        ProviderRefund r = provider.createRefund("pay_1", Money.inr("500.00"), refundId);

        assertThat(r.providerRefundId()).isEqualTo("rfnd_1");
        assertThat(r.status()).isEqualTo(ProviderRefund.Status.PENDING);
        assertThat(r.amount()).isEqualTo(Money.inr("500.00"));
        assertThat(r.ourRefundId()).isEqualTo(refundId.toString());
        server.verify(postRequestedFor(urlEqualTo("/v1/payments/pay_1/refund")).withRequestBody(equalToJson(
                "{\"amount\":50000,\"speed\":\"normal\",\"receipt\":\"" + refundId + "\",\"notes\":{\"refund_id\":\""
                        + refundId + "\"}}")));
    }

    @Test
    void mapsA400ToRejected() {
        server.stubFor(post("/v1/payments/pay_1/refund").willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"The refund amount provided is greater than amount captured\"}}")));

        assertThatThrownBy(() -> provider.createRefund("pay_1", Money.inr("1.00"), UUID.randomUUID()))
                .isInstanceOfSatisfying(ProviderRejectedException.class, e -> {
                    assertThat(e.code()).isEqualTo("BAD_REQUEST_ERROR");
                    assertThat(e.description()).contains("greater than amount captured");
                });
    }

    @Test
    void mapsA5xxToUnavailable() {
        server.stubFor(post("/v1/payments/pay_1/refund").willReturn(aResponse().withStatus(502)));
        assertThatThrownBy(() -> provider.createRefund("pay_1", Money.inr("1.00"), UUID.randomUUID()))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void mapsASlowResponseToTimeoutBecauseTheRefundMayExist() {
        server.stubFor(post("/v1/payments/pay_1/refund").willReturn(okJson("{}").withFixedDelay(1500)));
        assertThatThrownBy(() -> provider.createRefund("pay_1", Money.inr("1.00"), UUID.randomUUID()))
                .isInstanceOf(ProviderTimeoutException.class);
    }

    @Test
    void listsRefundsOfAPayment() {
        server.stubFor(get(urlPathEqualTo("/v1/payments/pay_1/refunds")).willReturn(okJson(
                "{\"entity\":\"collection\",\"count\":2,\"items\":[" + refundJson("rfnd_1", "processed", 100, "a") + ","
                        + refundJson("rfnd_2", "failed", 200, "b") + "]}")));

        assertThat(provider.fetchRefunds("pay_1")).extracting(ProviderRefund::providerRefundId, ProviderRefund::status)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("rfnd_1", ProviderRefund.Status.PROCESSED),
                        org.assertj.core.groups.Tuple.tuple("rfnd_2", ProviderRefund.Status.FAILED));
    }

    @Test
    void verifiesWebhookSignaturesOverTheRawBody() {
        byte[] body = "{\"event\":\"refund.processed\"}".getBytes(StandardCharsets.UTF_8);
        String signature = HmacSignatures.hmacSha256Hex("whsecret", body);
        assertThat(provider.verifyWebhookSignature(body, signature)).isTrue();
        assertThat(provider.verifyWebhookSignature(body, signature.replace(signature.charAt(0), 'x'))).isFalse();
        assertThat(provider.verifyWebhookSignature("{}".getBytes(StandardCharsets.UTF_8), signature)).isFalse();
    }

    @Test
    void parsesRefundWebhooks() {
        String body = "{\"event\":\"refund.processed\",\"payload\":{\"refund\":{\"entity\":"
                + refundJson("rfnd_9", "processed", 1234, "our-id") + "}}}";
        assertThat(provider.parseWebhook(body.getBytes(StandardCharsets.UTF_8))).hasValueSatisfying(r -> {
            assertThat(r.providerRefundId()).isEqualTo("rfnd_9");
            assertThat(r.status()).isEqualTo(ProviderRefund.Status.PROCESSED);
            assertThat(r.amount()).isEqualTo(Money.inr("12.34"));
        });
        assertThat(provider.parseWebhook("{\"event\":\"payment.captured\",\"payload\":{}}".getBytes(StandardCharsets.UTF_8)))
                .isEmpty();
    }
}
