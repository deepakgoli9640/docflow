package com.hpg.notification.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.hpg.common.id.Ids;
import com.hpg.common.money.Money;
import com.hpg.events.EventEnvelope;
import com.hpg.events.Topics;
import com.hpg.events.payment.PaymentEventPayload;
import com.hpg.events.refund.RefundEventPayload;
import com.hpg.messaging.outbox.OutboxEvent;
import com.hpg.messaging.outbox.OutboxEventRepository;
import com.hpg.notification.application.NotificationDispatcher;
import com.hpg.notification.delivery.DeliveryResult;
import com.hpg.notification.delivery.EmailSender;
import com.hpg.notification.delivery.WebhookSigner;
import com.hpg.notification.domain.Channel;
import com.hpg.notification.domain.Notification;
import com.hpg.notification.domain.NotificationStatus;
import com.hpg.notification.infrastructure.persistence.NotificationRepository;
import com.hpg.notification.support.KafkaTestContainer;
import com.hpg.notification.support.PostgresTestContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Events in through Kafka; signed webhooks out to a WireMock "hospital", configuration from a WireMock
 * "merchant-service" (token + internal webhook-config endpoint). Email is a mock port.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({PostgresTestContainer.class, KafkaTestContainer.class})
class NotificationFlowIT {

    static final WireMockServer wiremock = new WireMockServer(wireMockConfig().dynamicPort());
    static final String SECRET = "whsec_test_hospital";

    static {
        wiremock.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("hpg.notification.merchant-service-uri", wiremock::baseUrl);
        registry.add("hpg.messaging.outbox.relay.enabled", () -> "false");
    }

    @AfterAll
    static void stop() {
        wiremock.stop();
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    NotificationDispatcher dispatcher;
    @Autowired
    NotificationRepository notifications;
    @Autowired
    OutboxEventRepository outbox;
    @Autowired
    JdbcTemplate jdbc;
    @MockitoBean
    EmailSender emails;

    UUID merchantId;

    @BeforeEach
    void setUp() {
        merchantId = UUID.randomUUID();
        wiremock.resetAll();
        wiremock.stubFor(WireMock.post("/oauth2/token").willReturn(okJson(
                "{\"access_token\":\"svc-token\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
        hospitalConfig(merchantId, wiremock.baseUrl() + "/hooks/" + merchantId);
        when(emails.send(any())).thenReturn(DeliveryResult.ok(null));
    }

    void hospitalConfig(UUID merchant, String webhookUrl) {
        wiremock.stubFor(WireMock.get("/internal/v1/merchants/" + merchant + "/webhook-config")
                .withHeader("Authorization", WireMock.equalTo("Bearer svc-token"))
                .willReturn(okJson("{\"merchantId\":\"" + merchant + "\",\"merchantStatus\":\"ACTIVE\",\"webhookUrl\":"
                        + (webhookUrl == null ? "null" : "\"" + webhookUrl + "\"") + ",\"webhookSecret\":\"" + SECRET + "\"}")));
    }

    RequestPostProcessor merchant() {
        return jwt().jwt(j -> j.claim("mid", merchantId.toString())).authorities(new SimpleGrantedAuthority("ROLE_MERCHANT"));
    }

    UUID paymentEvent(String type, String status, String email) throws Exception {
        UUID paymentId = Ids.newId();
        var payload = new PaymentEventPayload(paymentId, merchantId, "BILL-9", Money.inr("2000.00"), status, "PROCESSING",
                "AUTO", "RAZORPAY", "order_9", "pay_9", false, null, null, Instant.now(),
                email == null ? null : new PaymentEventPayload.Customer("Asha", email, "+919800000000"));
        EventEnvelope<PaymentEventPayload> env = new EventEnvelope<>(Ids.newId(), type, 1, Instant.now(), "payment-service",
                "Payment", paymentId.toString(), 4, merchantId, "corr", null, payload);
        kafka.send(Topics.PAYMENT_EVENTS, paymentId.toString(), json.writeValueAsString(env)).get();
        return paymentId;
    }

    List<Notification> awaitNotifications(UUID paymentId, int count) {
        await().atMost(Duration.ofSeconds(60)).until(() -> notifications.findByPaymentIdOrderByCreatedAtAsc(paymentId).size() >= count);
        return notifications.findByPaymentIdOrderByCreatedAtAsc(paymentId);
    }

    void dispatchUntilQuiet() {
        for (int i = 0; i < 10 && dispatcher.dispatchDue() > 0; i++) {
            // tests use a 1 ms backoff, so retries are due immediately
        }
    }

    @Test
    void capturedPaymentSendsASignedWebhookAndAPatientReceipt() throws Exception {
        wiremock.stubFor(WireMock.post(urlEqualTo("/hooks/" + merchantId)).willReturn(aResponse().withStatus(204)));
        UUID paymentId = paymentEvent("PaymentCaptured", "CAPTURED", "asha@example.com");

        List<Notification> created = awaitNotifications(paymentId, 2);
        assertThat(created).extracting(Notification::getChannel).containsExactlyInAnyOrder(Channel.WEBHOOK, Channel.EMAIL);
        dispatchUntilQuiet();

        List<LoggedRequest> requests = wiremock.findAll(postRequestedFor(urlEqualTo("/hooks/" + merchantId)));
        assertThat(requests).hasSize(1);
        LoggedRequest hook = requests.getFirst();
        String body = hook.getBodyAsString();
        long ts = Long.parseLong(hook.getHeader(WebhookSigner.TIMESTAMP));
        assertThat(hook.getHeader(WebhookSigner.SIGNATURE)).isEqualTo(WebhookSigner.sign(SECRET, ts, body));
        assertThat(hook.getHeader(WebhookSigner.EVENT_TYPE)).isEqualTo("payment.captured");
        JsonNode payload = json.readTree(body);
        assertThat(payload.get("type").asText()).isEqualTo("payment.captured");
        assertThat(payload.get("sequence").asLong()).isEqualTo(4);
        assertThat(payload.at("/data/paymentId").asText()).isEqualTo(paymentId.toString());
        assertThat(payload.at("/data/amount/amount").asText()).isEqualTo("2000.00");
        assertThat(body).doesNotContain("asha@example.com"); // hospital webhooks carry no patient contact data

        verify(emails).send(any());
        assertThat(notifications.findByPaymentIdOrderByCreatedAtAsc(paymentId)).allSatisfy(n ->
                assertThat(n.getStatus()).isEqualTo(NotificationStatus.DELIVERED));
        // The patient's address is encrypted at rest.
        assertThat(jdbc.queryForList("SELECT recipient_enc FROM notifications WHERE payment_id = ? AND channel = 'EMAIL'",
                String.class, paymentId).getFirst()).doesNotContain("asha");

        mvc.perform(get("/api/v1/notifications").param("paymentId", paymentId.toString()).with(merchant()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[?(@.channel == 'EMAIL')].recipient").value(org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("asha@")))));
    }

    @Test
    void failedDeliveriesAreRetriedThenSucceed() throws Exception {
        String url = "/hooks/" + merchantId;
        wiremock.stubFor(WireMock.post(urlEqualTo(url)).inScenario("flaky").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("up"));
        wiremock.stubFor(WireMock.post(urlEqualTo(url)).inScenario("flaky").whenScenarioStateIs("up")
                .willReturn(aResponse().withStatus(200)));
        UUID paymentId = paymentEvent("PaymentFailed", "FAILED", null);

        Notification n = awaitNotifications(paymentId, 1).getFirst();
        dispatchUntilQuiet();

        mvc.perform(get("/api/v1/notifications/{id}", n.getId()).with(merchant()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.attempts").value(2))
                .andExpect(jsonPath("$.attemptLog[0].outcome").value("RETRY"))
                .andExpect(jsonPath("$.attemptLog[0].httpStatus").value(503))
                .andExpect(jsonPath("$.attemptLog[1].outcome").value("DELIVERED"));
    }

    @Test
    void givesUpAfterTheRetryBudgetPublishesNotificationFailedAndOpsCanRequeue() throws Exception {
        wiremock.stubFor(WireMock.post(urlEqualTo("/hooks/" + merchantId)).willReturn(aResponse().withStatus(500)));
        UUID paymentId = paymentEvent("PaymentExpired", "EXPIRED", null);
        Notification n = awaitNotifications(paymentId, 1).getFirst();
        dispatchUntilQuiet();

        Notification dead = notifications.findById(n.getId()).orElseThrow();
        assertThat(dead.getStatus()).isEqualTo(NotificationStatus.DEAD);
        assertThat(dead.getAttempts()).isEqualTo(3);
        assertThat(outbox.findByAggregateIdOrderByIdAsc(n.getId().toString()))
                .extracting(OutboxEvent::getEventType).containsExactly("NotificationFailed");

        // OPS finds it across merchants, reads its attempts, and re-queues it.
        var ops = jwt().authorities(new SimpleGrantedAuthority("ROLE_OPS"));
        mvc.perform(get("/api/v1/admin/notifications").param("status", "DEAD").param("merchantId", merchantId.toString())
                        .with(ops))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(n.getId().toString()))
                .andExpect(jsonPath("$.content[0].merchantId").value(merchantId.toString()));
        mvc.perform(get("/api/v1/admin/notifications/{id}", n.getId()).with(ops))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptLog.length()").value(3));
        mvc.perform(get("/api/v1/admin/notifications").with(merchant()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/notifications/{id}/retry", n.getId()).with(ops))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(post("/api/v1/admin/notifications/{id}/retry", n.getId()).with(merchant()))
                .andExpect(status().isForbidden());
    }

    @Test
    void hospitalWithoutWebhookUrlIsSkipped() throws Exception {
        hospitalConfig(merchantId, null);
        UUID paymentId = paymentEvent("PaymentCancelled", "CANCELLED", null);
        Notification n = awaitNotifications(paymentId, 1).getFirst();
        dispatchUntilQuiet();
        assertThat(notifications.findById(n.getId()).orElseThrow().getStatus()).isEqualTo(NotificationStatus.SKIPPED);
    }

    @Test
    void completedRefundNotifiesTheHospitalOnce() throws Exception {
        wiremock.stubFor(WireMock.post(urlEqualTo("/hooks/" + merchantId)).willReturn(aResponse().withStatus(200)));
        UUID paymentId = Ids.newId();
        UUID refundId = Ids.newId();
        var payload = new RefundEventPayload(refundId, paymentId, merchantId, "BILL-9", Money.inr("500.00"),
                Money.inr("500.00"), "COMPLETED", "partial", "rfnd_9", null, null, Instant.now());
        String env = json.writeValueAsString(new EventEnvelope<>(Ids.newId(), "RefundCompleted", 1, Instant.now(),
                "refund-service", "Refund", refundId.toString(), 2, merchantId, "corr", null, payload));
        kafka.send(Topics.REFUND_EVENTS, paymentId.toString(), env).get();
        kafka.send(Topics.REFUND_EVENTS, paymentId.toString(), env).get(); // duplicate delivery

        Notification n = awaitNotifications(paymentId, 1).getFirst();
        dispatchUntilQuiet();
        assertThat(notifications.findByPaymentIdOrderByCreatedAtAsc(paymentId)).hasSize(1);
        assertThat(n.getPublicEventType()).isEqualTo("refund.completed");
        LoggedRequest hook = wiremock.findAll(postRequestedFor(urlEqualTo("/hooks/" + merchantId))).getFirst();
        assertThat(json.readTree(hook.getBodyAsString()).at("/data/refundedTotal/amount").asText()).isEqualTo("500.00");
    }
}
