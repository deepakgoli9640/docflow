package com.hpg.refund.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.hpg.common.id.Ids;
import com.hpg.common.money.Money;
import com.hpg.events.EventEnvelope;
import com.hpg.events.Topics;
import com.hpg.events.payment.PaymentEventPayload;
import com.hpg.messaging.outbox.OutboxEvent;
import com.hpg.messaging.outbox.OutboxEventRepository;
import com.hpg.refund.application.RefundRecoveryService;
import com.hpg.refund.infrastructure.persistence.RefundablePaymentStore;
import com.hpg.refund.provider.HmacSignatures;
import com.hpg.refund.support.KafkaTestContainer;
import com.hpg.refund.support.PostgresTestContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * refund-service end to end: PaymentCaptured arrives through Kafka, refunds go to a WireMock Razorpay,
 * webhooks and recovery converge them, and refund events land in the outbox.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({PostgresTestContainer.class, KafkaTestContainer.class})
class RefundFlowIT {

    static final WireMockServer razorpay = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        razorpay.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("hpg.refund.razorpay.base-url", razorpay::baseUrl);
        registry.add("hpg.messaging.outbox.relay.enabled", () -> "false"); // assert on the outbox table
    }

    @AfterAll
    static void stop() {
        razorpay.stop();
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    RefundablePaymentStore refundables;
    @Autowired
    RefundRecoveryService recovery;
    @Autowired
    OutboxEventRepository outbox;

    UUID merchantId;

    @BeforeEach
    void reset() {
        merchantId = UUID.randomUUID();
        razorpay.resetAll();
    }

    RequestPostProcessor merchant() {
        return jwt().jwt(j -> j.claim("mid", merchantId.toString())).authorities(new SimpleGrantedAuthority("ROLE_MERCHANT"));
    }

    /** Publishes PaymentCaptured as payment-service would and waits until refund-service has consumed it. */
    UUID capturedPayment(String amount, String providerPaymentId) throws Exception {
        UUID paymentId = Ids.newId();
        PaymentEventPayload payload = new PaymentEventPayload(paymentId, merchantId, "BILL-" + paymentId.toString().substring(0, 8),
                Money.inr(amount), "CAPTURED", "PROCESSING", "AUTO", "RAZORPAY", "order_1", providerPaymentId, false,
                null, null, Instant.now(), null);
        EventEnvelope<PaymentEventPayload> envelope = new EventEnvelope<>(Ids.newId(), "PaymentCaptured", 1, Instant.now(),
                "payment-service", "Payment", paymentId.toString(), 3, merchantId, "corr", "test", payload);
        String value = json.writeValueAsString(envelope);
        kafka.send(Topics.PAYMENT_EVENTS, paymentId.toString(), value).get();
        kafka.send(Topics.PAYMENT_EVENTS, paymentId.toString(), value).get(); // redelivery must be harmless
        await().atMost(Duration.ofSeconds(60)).until(() -> refundables.find(paymentId).isPresent());
        return paymentId;
    }

    MvcResult refund(UUID paymentId, String amount, String key) throws Exception {
        return mvc.perform(post("/api/v1/refunds").with(merchant()).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentId\":\"" + paymentId + "\",\"amount\":{\"amount\":\"" + amount
                                + "\",\"currency\":\"INR\"},\"reason\":\"partial cover\"}"))
                .andReturn();
    }

    static String refundJson(String id, String status, long paise, String ourId) {
        return """
                {"id":"%s","entity":"refund","amount":%d,"currency":"INR","payment_id":"pay_x",
                 "notes":{"refund_id":"%s"},"receipt":"%s","status":"%s"}""".formatted(id, paise, ourId, ourId, status);
    }

    @Test
    void partialRefundIsProcessedByWebhookAndCannotExceedTheCapturedAmount() throws Exception {
        UUID paymentId = capturedPayment("1000.00", "pay_A");
        razorpay.stubFor(WireMock.post("/v1/payments/pay_A/refund").willReturn(okJson(refundJson("rfnd_A1", "pending", 60000,
                "{{jsonPath request.body '$.receipt'}}")).withTransformers("response-template")));

        MvcResult first = refund(paymentId, "600.00", "key-partial-1");
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        JsonNode created = json.readTree(first.getResponse().getContentAsString());
        assertThat(created.get("status").asText()).isEqualTo("PROCESSING");
        UUID refundId = UUID.fromString(created.get("id").asText());

        // Replay with the same key: same refund, no second provider call.
        mvc.perform(post("/api/v1/refunds").with(merchant()).header("Idempotency-Key", "key-partial-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentId\":\"" + paymentId + "\",\"amount\":{\"amount\":\"600.00\",\"currency\":\"INR\"},\"reason\":\"partial cover\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(refundId.toString()));
        razorpay.verify(1, postRequestedFor(urlEqualTo("/v1/payments/pay_A/refund")));

        // 600 reserved: only 400 remains.
        MvcResult tooMuch = refund(paymentId, "500.00", "key-partial-2");
        assertThat(tooMuch.getResponse().getStatus()).isEqualTo(422);
        JsonNode problem = json.readTree(tooMuch.getResponse().getContentAsString());
        assertThat(problem.get("errorCode").asText()).isEqualTo("REFUND_EXCEEDS_CAPTURED_AMOUNT");
        assertThat(problem.at("/remainingRefundable/amount").asText()).isEqualTo("400.00");

        // Signed refund.processed webhook completes it.
        String body = "{\"entity\":\"event\",\"event\":\"refund.processed\",\"payload\":{\"refund\":{\"entity\":"
                + refundJson("rfnd_A1", "processed", 60000, refundId.toString()) + "}}}";
        String signature = HmacSignatures.hmacSha256Hex("test_webhook_secret", body.getBytes());
        for (int i = 0; i < 2; i++) { // the duplicate delivery is acknowledged and ignored
            mvc.perform(post("/webhooks/razorpay/refunds").header("X-Razorpay-Signature", signature)
                            .header("X-Razorpay-Event-Id", "evt_refund_A1").contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/webhooks/razorpay/refunds").header("X-Razorpay-Signature", "deadbeef")
                        .header("X-Razorpay-Event-Id", "evt_forged").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/refunds/{id}", refundId).with(merchant()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.providerRefundId").value("rfnd_A1"))
                .andExpect(jsonPath("$.statusHistory.length()").value(3));
        var row = refundables.find(paymentId).orElseThrow();
        assertThat(row.refunded()).isEqualTo(Money.inr("600.00"));
        assertThat(row.remainingRefundable()).isEqualTo(Money.inr("400.00"));

        List<OutboxEvent> events = outbox.findByAggregateIdOrderByIdAsc(refundId.toString());
        assertThat(events).extracting(OutboxEvent::getEventType).containsExactly("RefundInitiated", "RefundCompleted");
        assertThat(events).allSatisfy(e -> {
            assertThat(e.getMessageKey()).isEqualTo(paymentId.toString());
            assertThat(e.getTopic()).isEqualTo(Topics.REFUND_EVENTS);
        });
        assertThat(events.get(1).getPayload().at("/payload/refundedTotal/amount").asText()).isEqualTo("600.00");

        // Another merchant cannot see it.
        mvc.perform(get("/api/v1/refunds/{id}", refundId).with(jwt().jwt(j -> j.claim("mid", UUID.randomUUID().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_MERCHANT"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void concurrentRefundsNeverExceedTheCapturedAmount() throws Exception {
        UUID paymentId = capturedPayment("1000.00", "pay_C");
        razorpay.stubFor(WireMock.post("/v1/payments/pay_C/refund").willReturn(okJson(refundJson("rfnd_{{randomValue length=8 type='ALPHANUMERIC'}}",
                "processed", 20000, "{{jsonPath request.body '$.receipt'}}")).withTransformers("response-template")));

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String key = "concurrent-" + i + "-" + paymentId.toString().substring(0, 8);
            Callable<Integer> call = () -> refund(paymentId, "200.00", key).getResponse().getStatus();
            results.add(pool.submit(call));
        }
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) {
            statuses.add(f.get());
        }
        pool.shutdown();

        assertThat(statuses).filteredOn(s -> s == 201).hasSize(5);
        assertThat(statuses).filteredOn(s -> s == 422).hasSize(3);
        var row = refundables.find(paymentId).orElseThrow();
        assertThat(row.refunded()).isEqualTo(Money.inr("1000.00"));
        assertThat(row.remainingRefundable()).isEqualTo(Money.inr("0.00"));
    }

    @Test
    void timedOutRefundIsLookedUpBeforeAnyRetry() throws Exception {
        UUID paymentId = capturedPayment("300.00", "pay_T");
        razorpay.stubFor(WireMock.post("/v1/payments/pay_T/refund").willReturn(okJson("{}").withFixedDelay(1500)));

        MvcResult result = refund(paymentId, "300.00", "key-timeout-1");
        JsonNode created = json.readTree(result.getResponse().getContentAsString());
        assertThat(created.get("status").asText()).isEqualTo("UNKNOWN");
        String refundId = created.get("id").asText();

        // The provider did create it: recovery must adopt it, not send a second refund.
        razorpay.stubFor(WireMock.get(urlPathEqualTo("/v1/payments/pay_T/refunds")).willReturn(okJson(
                "{\"entity\":\"collection\",\"count\":1,\"items\":[" + refundJson("rfnd_T1", "processed", 30000, refundId)
                        + "]}")));
        recovery.recover();

        mvc.perform(get("/api/v1/refunds/{id}", refundId).with(merchant()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.providerRefundId").value("rfnd_T1"));
        razorpay.verify(1, postRequestedFor(urlEqualTo("/v1/payments/pay_T/refund")));
    }

    @Test
    void rejectedRefundReleasesTheReservation() throws Exception {
        UUID paymentId = capturedPayment("250.00", "pay_R");
        razorpay.stubFor(WireMock.post("/v1/payments/pay_R/refund").willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"Payment is not refundable\"}}")));

        JsonNode created = json.readTree(refund(paymentId, "250.00", "key-rejected-1").getResponse().getContentAsString());
        assertThat(created.get("status").asText()).isEqualTo("FAILED");
        assertThat(created.get("failureCode").asText()).isEqualTo("BAD_REQUEST_ERROR");
        assertThat(refundables.find(paymentId).orElseThrow().remainingRefundable()).isEqualTo(Money.inr("250.00"));
        assertThat(outbox.findByAggregateIdOrderByIdAsc(created.get("id").asText()))
                .extracting(OutboxEvent::getEventType).containsExactly("RefundInitiated", "RefundFailed");
    }

    @Test
    void unknownPaymentIsNotYetRefundable() throws Exception {
        MvcResult result = refund(UUID.randomUUID(), "10.00", "key-unknown-1");
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(result.getResponse().getContentAsString()).get("errorCode").asText())
                .isEqualTo("PAYMENT_NOT_YET_REFUNDABLE");
    }

    @Test
    void requiresIdempotencyKeyAndValidAmount() throws Exception {
        mvc.perform(post("/api/v1/refunds").with(merchant()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentId\":\"" + UUID.randomUUID() + "\",\"amount\":{\"amount\":\"1.00\",\"currency\":\"INR\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MISSING_IDEMPOTENCY_KEY"));
        mvc.perform(post("/api/v1/refunds").with(merchant()).header("Idempotency-Key", "key-invalid-amount")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentId\":\"" + UUID.randomUUID() + "\",\"amount\":{\"amount\":\"-1.00\",\"currency\":\"INR\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/v1/refunds").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
