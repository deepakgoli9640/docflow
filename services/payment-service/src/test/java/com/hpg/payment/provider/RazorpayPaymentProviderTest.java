package com.hpg.payment.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.hpg.common.money.Money;
import com.hpg.payment.domain.CaptureMode;
import com.hpg.payment.provider.razorpay.RazorpayPaymentProvider;
import com.hpg.payment.provider.razorpay.RazorpayProperties;
import com.hpg.payment.provider.resilience.ResilientPaymentProvider;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Razorpay adapter against WireMock stubs that follow Razorpay's documented API shapes.
 * <b>These are simulations of Razorpay, not Razorpay.</b> They prove our request building, parsing,
 * error mapping and timeout behaviour; the sandbox run (Phase 3 exit criterion, once test keys exist)
 * proves the shapes themselves.
 */
class RazorpayPaymentProviderTest {

    @RegisterExtension
    static WireMockExtension razorpay = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static final String KEY_ID = "rzp_test_dummykey";
    private static final String KEY_SECRET = "test_key_secret";
    private static final String WEBHOOK_SECRET = "test_webhook_secret";

    private RazorpayPaymentProvider provider;

    @BeforeEach
    void setUp() {
        RazorpayProperties properties = new RazorpayProperties(razorpay.baseUrl(), KEY_ID, KEY_SECRET, WEBHOOK_SECRET,
                null, Duration.ofMillis(500), Duration.ofMillis(800));
        provider = new RazorpayPaymentProvider(properties, new ObjectMapper());
    }

    private static String payment(String id, String status) {
        return """
                {"id":"%s","entity":"payment","amount":2000000,"currency":"INR","status":"%s",
                 "order_id":"order_ABC","method":"upi","captured":%s,"error_code":null,"error_description":null}"""
                .formatted(id, status, "captured".equals(status));
    }

    @Test
    void createOrderSendsPaiseBasicAuthAndCaptureMode() {
        razorpay.stubFor(post("/v1/orders").willReturn(okJson("""
                {"id":"order_ABC","entity":"order","amount":2000000,"currency":"INR","receipt":"r","status":"created"}""")));

        ProviderOrder order = provider.createOrder(new CreateOrderCommand(UUID.randomUUID(), "pay-123",
                Money.inr("20000.00"), CaptureMode.MANUAL, Map.of("payment_id", "pay-123")));

        assertThat(order.providerOrderId()).isEqualTo("order_ABC");
        assertThat(order.amount()).isEqualTo(Money.inr("20000.00"));
        String basic = "Basic " + Base64.getEncoder().encodeToString((KEY_ID + ":" + KEY_SECRET).getBytes(StandardCharsets.UTF_8));
        razorpay.verify(postRequestedFor(urlEqualTo("/v1/orders"))
                .withHeader("Authorization", equalTo(basic))
                .withRequestBody(equalToJson("""
                        {"amount":2000000,"currency":"INR","receipt":"pay-123","notes":{"payment_id":"pay-123"},
                         "payment":{"capture":"manual"}}""")));
    }

    @Test
    void paiseConversionIsExact() {
        razorpay.stubFor(post("/v1/orders").willReturn(okJson("""
                {"id":"order_X","amount":1,"currency":"INR","status":"created"}""")));
        ProviderOrder order = provider.createOrder(new CreateOrderCommand(UUID.randomUUID(), "r", Money.inr("0.01"),
                CaptureMode.AUTO, Map.of()));
        assertThat(order.amount()).isEqualTo(Money.inr("0.01"));
        razorpay.verify(postRequestedFor(urlEqualTo("/v1/orders")).withRequestBody(equalToJson(
                "{\"amount\":1}", true, true)));
    }

    @Test
    void badRequestIsANonRetryableRejectionWithProviderCode() {
        razorpay.stubFor(post("/v1/orders").willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                .withBody("""
                        {"error":{"code":"BAD_REQUEST_ERROR","description":"Order amount less than minimum amount allowed"}}""")));
        assertThatThrownBy(() -> provider.createOrder(new CreateOrderCommand(UUID.randomUUID(), "r", Money.inr("0.50"),
                CaptureMode.AUTO, Map.of())))
                .isInstanceOf(ProviderRejectedException.class)
                .satisfies(e -> assertThat(((ProviderRejectedException) e).providerErrorCode()).isEqualTo("BAD_REQUEST_ERROR"));
    }

    @Test
    void serverErrorIsUnavailable() {
        razorpay.stubFor(get("/v1/payments/pay_1").willReturn(aResponse().withStatus(502)));
        assertThatThrownBy(() -> provider.fetchPayment("pay_1")).isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void unauthorizedIsAConfigurationError() {
        razorpay.stubFor(get("/v1/payments/pay_1").willReturn(aResponse().withStatus(401)));
        assertThatThrownBy(() -> provider.fetchPayment("pay_1")).isInstanceOf(ProviderAuthenticationException.class);
    }

    @Test
    void tooManyRequestsIsRateLimited() {
        razorpay.stubFor(get("/v1/payments/pay_1").willReturn(aResponse().withStatus(429)));
        assertThatThrownBy(() -> provider.fetchPayment("pay_1")).isInstanceOf(ProviderRateLimitedException.class);
    }

    @Test
    void slowResponseIsATimeoutWithUnknownOutcome() {
        razorpay.stubFor(post("/v1/payments/pay_1/capture").willReturn(okJson(payment("pay_1", "captured"))
                .withFixedDelay(2_000)));
        assertThatThrownBy(() -> provider.capture("pay_1", Money.inr("20000.00")))
                .isInstanceOf(ProviderTimeoutException.class);
    }

    @Test
    void connectionResetAfterSendingIsAlsoUnknownOutcome() {
        razorpay.stubFor(post("/v1/payments/pay_1/capture").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        assertThatThrownBy(() -> provider.capture("pay_1", Money.inr("20000.00")))
                .isInstanceOf(ProviderTimeoutException.class);
    }

    @Test
    void connectionRefusedIsUnavailable() {
        RazorpayProperties down = new RazorpayProperties("http://localhost:1", KEY_ID, KEY_SECRET, WEBHOOK_SECRET,
                null, Duration.ofMillis(300), Duration.ofMillis(300));
        RazorpayPaymentProvider unreachable = new RazorpayPaymentProvider(down, new ObjectMapper());
        assertThatThrownBy(() -> unreachable.fetchPayment("pay_1")).isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void repeatedCaptureOfAnAlreadyCapturedPaymentIsSuccess() {
        razorpay.stubFor(post("/v1/payments/pay_1/capture").willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"This payment has already been captured\"}}")));
        razorpay.stubFor(get("/v1/payments/pay_1").willReturn(okJson(payment("pay_1", "captured"))));

        ProviderPayment result = provider.capture("pay_1", Money.inr("20000.00"));
        assertThat(result.status()).isEqualTo(ProviderPaymentStatus.CAPTURED);
    }

    @Test
    void captureRejectionStandsWhenPaymentIsNotCaptured() {
        razorpay.stubFor(post("/v1/payments/pay_1/capture").willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"Capture amount mismatch\"}}")));
        razorpay.stubFor(get("/v1/payments/pay_1").willReturn(okJson(payment("pay_1", "authorized"))));
        assertThatThrownBy(() -> provider.capture("pay_1", Money.inr("1.00"))).isInstanceOf(ProviderRejectedException.class);
    }

    @Test
    void fetchOrderPaymentsMapsStatuses() {
        razorpay.stubFor(get("/v1/orders/order_ABC/payments").willReturn(okJson("""
                {"entity":"collection","count":2,"items":[%s,%s]}""".formatted(payment("pay_1", "failed"), payment("pay_2", "captured")))));
        var payments = provider.fetchOrderPayments("order_ABC");
        assertThat(payments).extracting(ProviderPayment::status)
                .containsExactly(ProviderPaymentStatus.FAILED, ProviderPaymentStatus.CAPTURED);
        assertThat(payments.get(1).amount()).isEqualTo(Money.inr("20000.00"));
    }

    @Test
    void checkoutSignatureVerification() {
        String valid = HmacSignatures.hmacSha256Hex(KEY_SECRET, "order_ABC|pay_1".getBytes(StandardCharsets.UTF_8));
        assertThat(provider.verifyCheckoutSignature("order_ABC", "pay_1", valid)).isTrue();
        assertThat(provider.verifyCheckoutSignature("order_ABC", "pay_2", valid)).isFalse();
        assertThat(provider.verifyCheckoutSignature("order_ABC", "pay_1", "deadbeef")).isFalse();
        assertThat(provider.verifyCheckoutSignature("order_ABC", "pay_1", null)).isFalse();
    }

    @Test
    void webhookSignatureIsOverTheExactRawBody() {
        byte[] body = "{\"event\":\"payment.captured\",\"payload\":{}}".getBytes(StandardCharsets.UTF_8);
        String signature = HmacSignatures.hmacSha256Hex(WEBHOOK_SECRET, body);
        assertThat(provider.verifyWebhookSignature(body, signature)).isTrue();
        // Re-serialised JSON (one extra space) no longer matches: that is why we verify raw bytes.
        byte[] reformatted = "{\"event\": \"payment.captured\",\"payload\":{}}".getBytes(StandardCharsets.UTF_8);
        assertThat(provider.verifyWebhookSignature(reformatted, signature)).isFalse();
        // Signed with the key secret instead of the webhook secret: rejected.
        assertThat(provider.verifyWebhookSignature(body, HmacSignatures.hmacSha256Hex(KEY_SECRET, body))).isFalse();
    }

    @Test
    void parsesWebhookPaymentEntity() {
        byte[] body = """
                {"entity":"event","event":"payment.captured","contains":["payment"],
                 "payload":{"payment":{"entity":%s}},"created_at":1794474904}""".formatted(payment("pay_9", "captured"))
                .getBytes(StandardCharsets.UTF_8);
        ProviderWebhookEvent event = provider.parseWebhook("evt_1", body).orElseThrow();
        assertThat(event.eventType()).isEqualTo("payment.captured");
        assertThat(event.payment().providerPaymentId()).isEqualTo("pay_9");
        assertThat(event.payment().providerOrderId()).isEqualTo("order_ABC");
        assertThat(event.payment().isCaptured()).isTrue();
        assertThat(event.createdAt().getEpochSecond()).isEqualTo(1794474904L);
    }

    @Test
    void missingCredentialsFailFast() {
        assertThatThrownBy(() -> new RazorpayPaymentProvider(new RazorpayProperties(null, "", "", "", null, null, null),
                new ObjectMapper())).isInstanceOf(IllegalStateException.class).hasMessageContaining("RAZORPAY_KEY_ID");
    }

    // ---- resilience decorator ----------------------------------------------------------------------

    private ResilientPaymentProvider resilient(CircuitBreaker breaker) {
        IntervalFunction fast = IntervalFunction.of(Duration.ofMillis(10));
        Retry safe = Retry.of("safe", RetryConfig.custom().maxAttempts(3).intervalFunction(fast)
                .retryExceptions(ProviderUnavailableException.class).build());
        Retry idempotent = Retry.of("idem", RetryConfig.custom().maxAttempts(3).intervalFunction(fast)
                .retryExceptions(ProviderUnavailableException.class, ProviderTimeoutException.class).build());
        return new ResilientPaymentProvider(provider, breaker, Bulkhead.ofDefaults("b"), safe, idempotent,
                new SimpleMeterRegistry(), "test");
    }

    @Test
    void readsAreRetriedThroughTransientServerErrors() {
        razorpay.stubFor(get("/v1/payments/pay_1").inScenario("flaky").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("recovered"));
        razorpay.stubFor(get("/v1/payments/pay_1").inScenario("flaky").whenScenarioStateIs("recovered")
                .willReturn(okJson(payment("pay_1", "captured"))));

        assertThat(resilient(CircuitBreaker.ofDefaults("cb")).fetchPayment("pay_1").isCaptured()).isTrue();
        razorpay.verify(2, getRequestedFor(urlEqualTo("/v1/payments/pay_1")));
    }

    @Test
    void timedOutCaptureIsNeverRetried() {
        razorpay.stubFor(post("/v1/payments/pay_1/capture").willReturn(okJson(payment("pay_1", "captured"))
                .withFixedDelay(2_000)));
        assertThatThrownBy(() -> resilient(CircuitBreaker.ofDefaults("cb")).capture("pay_1", Money.inr("20000.00")))
                .isInstanceOf(ProviderTimeoutException.class);
        razorpay.verify(1, postRequestedFor(urlEqualTo("/v1/payments/pay_1/capture")));
    }

    @Test
    void circuitOpensAfterRepeatedFailuresAndStopsCallingTheProvider() {
        razorpay.stubFor(get("/v1/payments/pay_1").willReturn(aResponse().withStatus(500)));
        CircuitBreaker breaker = CircuitBreaker.of("cb", CircuitBreakerConfig.custom()
                .slidingWindowSize(4).minimumNumberOfCalls(4).failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .recordExceptions(ProviderUnavailableException.class, ProviderTimeoutException.class)
                .ignoreExceptions(ProviderRejectedException.class).build());
        ResilientPaymentProvider p = resilient(breaker);
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> p.fetchPayment("pay_1")).isInstanceOf(ProviderUnavailableException.class);
        }
        int callsBefore = razorpay.findAll(getRequestedFor(urlEqualTo("/v1/payments/pay_1"))).size();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> p.fetchPayment("pay_1"))
                .isInstanceOf(ProviderUnavailableException.class).hasMessageContaining("circuit breaker open");
        assertThat(razorpay.findAll(getRequestedFor(urlEqualTo("/v1/payments/pay_1")))).hasSize(callsBefore);
    }

    @Test
    void rejectionsDoNotOpenTheCircuit() {
        razorpay.stubFor(get("/v1/payments/pay_1").willReturn(aResponse().withStatus(400)
                .withBody("{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"no such payment\"}}")));
        CircuitBreaker breaker = CircuitBreaker.of("cb", CircuitBreakerConfig.custom()
                .slidingWindowSize(2).minimumNumberOfCalls(2)
                .ignoreExceptions(ProviderRejectedException.class).build());
        ResilientPaymentProvider p = resilient(breaker);
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> p.fetchPayment("pay_1")).isInstanceOf(ProviderRejectedException.class);
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
