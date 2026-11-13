package com.hpg.notification.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.hpg.notification.domain.Channel;
import com.hpg.notification.domain.DeliveryOutcome;
import com.hpg.notification.domain.Notification;
import com.hpg.notification.domain.RecipientType;
import com.hpg.notification.infrastructure.client.MerchantServiceClient;
import com.hpg.notification.infrastructure.client.MerchantWebhookConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Webhook delivery against a stand-in hospital endpoint: signature, headers, and failure classification. */
class WebhookDeliveryChannelTest {

    private static final String SECRET = "whsec_test_secret_value";

    @RegisterExtension
    static WireMockExtension hospital = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-13T09:00:00Z"), ZoneOffset.UTC);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private MerchantServiceClient merchants;
    private WebhookDeliveryChannel channel;
    private UUID merchantId;

    @BeforeEach
    void setUp() {
        merchants = mock(MerchantServiceClient.class);
        merchantId = UUID.randomUUID();
        when(merchants.webhookConfig(any())).thenReturn(new MerchantWebhookConfig(merchantId.toString(), "ACTIVE",
                hospital.baseUrl() + "/hooks", SECRET));
        // Private targets allowed: WireMock listens on localhost. The guard itself is tested separately.
        channel = new WebhookDeliveryChannel(merchants, new PrivateNetworkGuard(true), json,
                Duration.ofSeconds(2), Duration.ofSeconds(2), clock);
    }

    private Notification notification(String eventType) {
        var payload = json.createObjectNode();
        payload.put("paymentId", UUID.randomUUID().toString());
        payload.put("merchantReference", "BILL-2026-000981");
        payload.put("status", "CAPTURED");
        payload.put("providerPaymentId", "pay_N1a2b3c4");
        payload.putObject("amount").put("amount", "20000.00").put("currency", "INR");
        payload.putObject("customer").put("email", "patient@example.com").put("phone", "+919800001234");
        return Notification.create(UUID.randomUUID(), eventType, Channel.WEBHOOK, RecipientType.MERCHANT,
                hospital.baseUrl() + "/hooks", merchantId, UUID.randomUUID(), 5, payload, clock.instant());
    }

    @Test
    void deliversASignedRequestTheHospitalCanVerify() {
        hospital.stubFor(post("/hooks").willReturn(ok()));

        DeliveryResult result = channel.deliver(notification("PaymentCaptured"), null);

        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.DELIVERED);
        assertThat(result.responseCode()).isEqualTo(200);
        var request = hospital.findAll(postRequestedFor(urlEqualTo("/hooks"))).get(0);
        String body = request.getBodyAsString();
        String timestamp = request.getHeader(WebhookDeliveryChannel.HEADER_TIMESTAMP);
        // This is exactly what the hospital's own code does: HMAC over "<timestamp>.<raw body>".
        String expected = "v1=" + WebhookDeliveryChannel.sign(SECRET, timestamp + "." + body);
        assertThat(request.getHeader(WebhookDeliveryChannel.HEADER_SIGNATURE)).isEqualTo(expected);
        assertThat(timestamp).isEqualTo(String.valueOf(clock.instant().getEpochSecond()));
        assertThat(request.getHeader(WebhookDeliveryChannel.HEADER_EVENT_TYPE)).isEqualTo("payment.captured");
        assertThat(request.getHeader(WebhookDeliveryChannel.HEADER_SEQUENCE)).isEqualTo("5");
        assertThat(request.getHeader(WebhookDeliveryChannel.HEADER_EVENT_ID)).isNotBlank();
    }

    @Test
    void signatureDependsOnTheSecretAndTheBody() {
        String body = "{\"a\":1}";
        assertThat(WebhookDeliveryChannel.sign(SECRET, "1.".concat(body)))
                .isNotEqualTo(WebhookDeliveryChannel.sign("other-secret", "1." + body))
                .isNotEqualTo(WebhookDeliveryChannel.sign(SECRET, "2." + body));
    }

    @Test
    void doesNotSendPatientContactDetailsBackToTheHospital() {
        hospital.stubFor(post("/hooks").willReturn(ok()));
        channel.deliver(notification("PaymentCaptured"), null);
        hospital.verify(postRequestedFor(urlEqualTo("/hooks"))
                .withRequestBody(matchingJsonPath("$.data.merchantReference", equalTo("BILL-2026-000981"))));
        assertThat(hospital.findAll(postRequestedFor(urlEqualTo("/hooks"))).get(0).getBodyAsString())
                .doesNotContain("patient@example.com")
                .doesNotContain("9800001234");
    }

    @Test
    void serverErrorIsRetryableAndGoneIsPermanent() {
        hospital.stubFor(post("/hooks").willReturn(aResponse().withStatus(503)));
        assertThat(channel.deliver(notification("PaymentCaptured"), null).outcome())
                .isEqualTo(DeliveryOutcome.RETRYABLE_FAILURE);

        hospital.resetAll();
        hospital.stubFor(post("/hooks").willReturn(aResponse().withStatus(410)));
        DeliveryResult gone = channel.deliver(notification("PaymentCaptured"), null);
        assertThat(gone.outcome()).isEqualTo(DeliveryOutcome.PERMANENT_FAILURE);
        assertThat(gone.responseCode()).isEqualTo(410);
    }

    @Test
    void misconfiguredEndpointIsStillRetried() {
        // A 401 or 404 is usually a hospital mid-deployment, not a permanent answer.
        hospital.stubFor(post("/hooks").willReturn(aResponse().withStatus(401)));
        assertThat(channel.deliver(notification("PaymentCaptured"), null).outcome())
                .isEqualTo(DeliveryOutcome.RETRYABLE_FAILURE);
    }

    @Test
    void timeoutIsRetryable() {
        hospital.stubFor(post("/hooks").willReturn(ok().withFixedDelay(4_000)));
        assertThat(channel.deliver(notification("PaymentCaptured"), null).outcome())
                .isEqualTo(DeliveryOutcome.RETRYABLE_FAILURE);
    }

    @Test
    void redirectsAreNotFollowed() {
        // Following a redirect would let a hospital point our signed request at any host it likes.
        hospital.stubFor(post("/hooks").willReturn(aResponse().withStatus(302)
                .withHeader("Location", "https://elsewhere.example/steal")));
        assertThat(channel.deliver(notification("PaymentCaptured"), null).outcome())
                .isEqualTo(DeliveryOutcome.RETRYABLE_FAILURE);
    }

    @Test
    void anUnavailableSecretIsRetryableAndNothingIsSent() {
        when(merchants.webhookConfig(any()))
                .thenThrow(new MerchantServiceClient.MerchantConfigUnavailableException("circuit open", null));
        hospital.stubFor(post("/hooks").willReturn(ok()));

        assertThat(channel.deliver(notification("PaymentCaptured"), null).outcome())
                .isEqualTo(DeliveryOutcome.RETRYABLE_FAILURE);
        hospital.verify(0, postRequestedFor(urlEqualTo("/hooks")));
    }

    @Test
    void unknownMerchantIsPermanent() {
        when(merchants.webhookConfig(any()))
                .thenThrow(new MerchantServiceClient.MerchantNotFoundException("gone"));
        assertThat(channel.deliver(notification("PaymentCaptured"), null).outcome())
                .isEqualTo(DeliveryOutcome.PERMANENT_FAILURE);
    }

    @Test
    void privateTargetsAreRefusedWithoutCallingThem() {
        WebhookDeliveryChannel strict = new WebhookDeliveryChannel(merchants, new PrivateNetworkGuard(false), json,
                Duration.ofSeconds(1), Duration.ofSeconds(1), clock);
        hospital.stubFor(post("/hooks").willReturn(ok()));
        DeliveryResult result = strict.deliver(notification("PaymentCaptured"), null);
        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.PERMANENT_FAILURE);
        hospital.verify(0, postRequestedFor(urlEqualTo("/hooks")));
    }

    @Test
    void mapsInternalEventNamesToTheHospitalFacingContract() {
        assertThat(WebhookDeliveryChannel.webhookEventType("PaymentCaptured")).isEqualTo("payment.captured");
        assertThat(WebhookDeliveryChannel.webhookEventType("PaymentAuthorized")).isEqualTo("payment.authorized");
        assertThat(WebhookDeliveryChannel.webhookEventType("RefundCompleted")).isEqualTo("refund.completed");
    }
}
