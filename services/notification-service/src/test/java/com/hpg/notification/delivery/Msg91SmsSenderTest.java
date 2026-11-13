package com.hpg.notification.delivery;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.hpg.notification.application.SmsProperties;
import com.hpg.notification.domain.Channel;
import com.hpg.notification.domain.Notification;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

class Msg91SmsSenderTest {

    static final WireMockServer MSG91 = new WireMockServer(wireMockConfig().dynamicPort());

    @BeforeAll
    static void start() {
        MSG91.start();
    }

    @AfterAll
    static void stop() {
        MSG91.stop();
    }

    @BeforeEach
    void reset() {
        MSG91.resetAll();
    }

    Msg91SmsSender sender() {
        return new Msg91SmsSender(new SmsProperties.Msg91(MSG91.baseUrl(), "test-auth-key",
                Map.of("payment.captured", "tmpl_receipt")), Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    static Notification sms(String type, String phone) {
        return new Notification(UUID.randomUUID(), UUID.randomUUID(), "PaymentCaptured", Channel.SMS, type,
                UUID.randomUUID(), null, 1, phone, null, "Payment of INR 100.00 received for bill B-1. Thank you.",
                Instant.now());
    }

    @Test
    void sendsTheDltTemplateWithDigitsOnlyNumber() {
        MSG91.stubFor(WireMock.post("/api/v5/flow").willReturn(aResponse().withStatus(200).withBody("{\"type\":\"success\"}")));
        assertThat(sender().send(sms("payment.captured", "+91 98000-01234")).success()).isTrue();
        MSG91.verify(postRequestedFor(urlEqualTo("/api/v5/flow"))
                .withHeader("authkey", equalTo("test-auth-key"))
                .withRequestBody(equalToJson("""
                        {"template_id":"tmpl_receipt","short_url":"0",
                         "recipients":[{"mobiles":"919800001234","message":"${json-unit.any-string}"}]}""")));
    }

    @Test
    void permanentErrorsAreSkippedAndTransientOnesRetried() {
        MSG91.stubFor(WireMock.post("/api/v5/flow").willReturn(aResponse().withStatus(401)));
        DeliveryResult rejected = sender().send(sms("payment.captured", "+919800001234"));
        assertThat(rejected.success()).isFalse();
        assertThat(rejected.permanent()).isTrue();

        MSG91.stubFor(WireMock.post("/api/v5/flow").willReturn(aResponse().withStatus(503)));
        DeliveryResult unavailable = sender().send(sms("payment.captured", "+919800001234"));
        assertThat(unavailable.permanent()).isFalse();
        assertThat(unavailable.httpStatus()).isEqualTo(503);
    }

    @Test
    void messageTypesWithoutARegisteredTemplateAreNotSent() {
        DeliveryResult r = sender().send(sms("payment.expired", "+919800001234"));
        assertThat(r.permanent()).isTrue();
        assertThat(r.error()).contains("no DLT template");
        MSG91.verify(0, postRequestedFor(urlEqualTo("/api/v5/flow")));
    }
}
