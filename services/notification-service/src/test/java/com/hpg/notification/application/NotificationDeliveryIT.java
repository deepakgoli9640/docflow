package com.hpg.notification.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.hpg.common.money.Money;
import com.hpg.events.merchant.MerchantSnapshotPayload;
import com.hpg.notification.delivery.WebhookDeliveryChannel;
import com.hpg.notification.domain.Channel;
import com.hpg.notification.domain.Notification;
import com.hpg.notification.domain.NotificationStatus;
import com.hpg.notification.domain.RecipientType;
import com.hpg.notification.infrastructure.persistence.NotificationRepository;
import com.hpg.notification.support.PostgresTestContainer;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Notification intake and delivery end to end: a real PostgreSQL, a real SMTP server (GreenMail), and
 * WireMock standing in for both the hospital's endpoint and merchant-service.
 */
@SpringBootTest(properties = {
        "hpg.notification.allow-private-webhook-targets=true",  // WireMock listens on localhost
        "hpg.notification.max-attempts=3",
        "hpg.notification.retry-initial=1s"})
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class NotificationDeliveryIT {

    private static final String WEBHOOK_SECRET = "whsec_integration_secret";

    @RegisterExtension
    static final GreenMailExtension SMTP = new GreenMailExtension(ServerSetupTest.SMTP);

    static final WireMockServer EXTERNAL = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        EXTERNAL.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("hpg.notification.merchant-service-uri", EXTERNAL::baseUrl);
        registry.add("spring.mail.port", () -> ServerSetupTest.SMTP.getPort());
    }

    @AfterAll
    static void stopExternal() {
        EXTERNAL.stop();
    }

    @Autowired
    NotificationIntakeService intake;
    @Autowired
    NotificationDispatcher dispatcher;
    @Autowired
    NotificationAdminService admin;
    @Autowired
    MerchantReplicaService replicas;
    @Autowired
    NotificationRepository notifications;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate jdbc;

    UUID merchantId;
    UUID paymentId;

    @BeforeEach
    void setUp() {
        // The PostgreSQL container is shared by the tests in this class, so each one starts from an empty
        // queue. TRUNCATE rather than DELETE: notification_attempts refuses deletes by design.
        jdbc.execute("TRUNCATE notification_attempts, notifications, outbox_events");
        SecurityContextHolder.clearContext();
        EXTERNAL.resetAll();
        EXTERNAL.stubFor(post("/oauth2/token").willReturn(okJson(
                "{\"access_token\":\"service-token\",\"token_type\":\"Bearer\",\"expires_in\":900}")));
        merchantId = UUID.randomUUID();
        paymentId = UUID.randomUUID();
        EXTERNAL.stubFor(get(urlPathMatching("/internal/v1/merchants/.*/webhook-config")).willReturn(okJson("""
                {"merchantId":"%s","merchantStatus":"ACTIVE","webhookUrl":"%s/hooks","webhookSecret":"%s"}"""
                .formatted(merchantId, EXTERNAL.baseUrl(), WEBHOOK_SECRET))));
        seedMerchant(1, EXTERNAL.baseUrl() + "/hooks", true, true);
    }

    /** The admin service is guarded by a role, so the test authenticates the way an operator would. */
    void asOperator() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("ops-console", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_OPS"))));
    }

    void seedMerchant(long version, String webhookUrl, boolean email, boolean sms) {
        replicas.apply(new MerchantSnapshotPayload(merchantId, version, "City Hospital Pvt Ltd", "City Hospital",
                "ACTIVE", Set.of("UPI"), "AUTO", Money.inr("1.00"), Money.inr("200000.00"), Money.inr("1000000.00"),
                60, 3, 180, webhookUrl, email, sms, Instant.now()));
    }

    ObjectNode payload() {
        ObjectNode payload = json.createObjectNode();
        payload.put("paymentId", paymentId.toString());
        payload.put("merchantId", merchantId.toString());
        payload.put("merchantReference", "BILL-2026-000981");
        payload.put("status", "CAPTURED");
        payload.put("providerPaymentId", "pay_N1a2b3c4");
        payload.putObject("amount").put("amount", "20000.00").put("currency", "INR");
        payload.putObject("customer").put("name", "A Patient").put("email", "patient@example.com")
                .put("phone", "+919800001234");
        return payload;
    }

    List<Notification> intake(UUID eventId, String eventType) {
        return tx.execute(status -> intake.intake(eventId, eventType, merchantId, 5, payload()));
    }

    @Test
    void capturedPaymentNotifiesTheHospitalThePatientAndRecordsEveryAttempt() throws Exception {
        EXTERNAL.stubFor(post("/hooks").willReturn(ok()));
        UUID eventId = UUID.randomUUID();

        List<Notification> created = intake(eventId, "PaymentCaptured");
        assertThat(created).extracting(Notification::getChannel)
                .containsExactlyInAnyOrder(Channel.WEBHOOK, Channel.EMAIL, Channel.SMS);
        assertThat(created).allMatch(n -> n.getStatus() == NotificationStatus.PENDING);

        assertThat(dispatcher.dispatchDue()).isEqualTo(3);

        // The hospital got a signed callback.
        var request = EXTERNAL.findAll(postRequestedFor(urlEqualTo("/hooks"))).get(0);
        String expected = "v1=" + WebhookDeliveryChannel.sign(WEBHOOK_SECRET,
                request.getHeader(WebhookDeliveryChannel.HEADER_TIMESTAMP) + "." + request.getBodyAsString());
        assertThat(request.getHeader(WebhookDeliveryChannel.HEADER_SIGNATURE)).isEqualTo(expected);
        assertThat(request.getHeader(WebhookDeliveryChannel.HEADER_EVENT_ID)).isEqualTo(eventId.toString());

        // The patient got a receipt over real SMTP.
        assertThat(SMTP.waitForIncomingEmail(5_000, 1)).isTrue();
        MimeMessage[] received = SMTP.getReceivedMessages();
        assertThat(received[0].getSubject()).contains("Payment received").contains("City Hospital");
        assertThat(received[0].getAllRecipients()[0].toString()).isEqualTo("patient@example.com");
        assertThat(received[0].getContent().toString()).contains("20000.00").contains("BILL-2026-000981");

        for (Notification notification : created) {
            Notification stored = notifications.findById(notification.getId()).orElseThrow();
            assertThat(stored.getStatus()).isEqualTo(NotificationStatus.DELIVERED);
            assertThat(stored.getDeliveredAt()).isNotNull();
            assertThat(dispatcher.attemptsOf(stored.getId())).hasSize(1);
        }
        // The webhook was signed with a secret fetched once and cached, not once per notification.
        EXTERNAL.verify(1, com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(
                urlPathMatching("/internal/v1/merchants/.*/webhook-config")));
    }

    @Test
    void theSameEventTwiceProducesOneMessagePerRecipient() {
        EXTERNAL.stubFor(post("/hooks").willReturn(ok()));
        UUID eventId = UUID.randomUUID();

        assertThat(intake(eventId, "PaymentCaptured")).hasSize(3);
        assertThat(intake(eventId, "PaymentCaptured")).isEmpty();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE source_event_id = ?", Long.class,
                eventId)).isEqualTo(3);
    }

    @Test
    void recipientsAreEncryptedAtRest() {
        intake(UUID.randomUUID(), "PaymentCaptured");
        List<String> stored = jdbc.queryForList(
                "SELECT recipient_enc FROM notifications WHERE channel = 'EMAIL'", String.class);
        assertThat(stored).isNotEmpty();
        assertThat(stored.get(0)).startsWith("v1:").doesNotContain("patient@example.com");
    }

    @Test
    void anEndpointThatKeepsFailingIsRetriedThenDeclaredDeadAndPublished() {
        EXTERNAL.stubFor(post("/hooks").willReturn(aResponse().withStatus(500)));
        seedMerchant(2, EXTERNAL.baseUrl() + "/hooks", false, false);
        UUID eventId = UUID.randomUUID();
        UUID notificationId = intake(eventId, "PaymentCaptured").get(0).getId();

        dispatcher.dispatchDue();
        Notification afterFirst = notifications.findById(notificationId).orElseThrow();
        assertThat(afterFirst.getStatus()).isEqualTo(NotificationStatus.RETRYING);
        assertThat(afterFirst.getNextAttemptAt()).isAfter(Instant.now());
        assertThat(afterFirst.getLastError()).contains("500");

        // Second and third attempts: with max-attempts=3 the third exhausts the budget.
        for (int i = 0; i < 2; i++) {
            jdbc.update("UPDATE notifications SET next_attempt_at = now() - interval '1 second' WHERE id = ?",
                    notificationId);
            dispatcher.dispatchDue();
        }

        Notification dead = notifications.findById(notificationId).orElseThrow();
        assertThat(dead.getStatus()).isEqualTo(NotificationStatus.DEAD);
        assertThat(dead.getAttemptCount()).isEqualTo(3);
        assertThat(dispatcher.attemptsOf(notificationId)).hasSize(3)
                .allMatch(a -> a.getOutcome() == com.hpg.notification.domain.DeliveryOutcome.RETRYABLE_FAILURE);
        // A dead notification is published so it can be alerted on.
        assertThat(jdbc.queryForList("SELECT event_type FROM outbox_events WHERE aggregate_id = ?", String.class,
                notificationId.toString())).containsExactly("NotificationFailed");
    }

    @Test
    void opsCanRequeueADeadNotificationOnceTheEndpointIsFixed() {
        EXTERNAL.stubFor(post("/hooks").willReturn(aResponse().withStatus(500)));
        seedMerchant(2, EXTERNAL.baseUrl() + "/hooks", false, false);
        UUID notificationId = intake(UUID.randomUUID(), "PaymentCaptured").get(0).getId();
        for (int i = 0; i < 3; i++) {
            jdbc.update("UPDATE notifications SET next_attempt_at = now() - interval '1 second' WHERE id = ?",
                    notificationId);
            dispatcher.dispatchDue();
        }
        assertThat(notifications.findById(notificationId).orElseThrow().getStatus())
                .isEqualTo(NotificationStatus.DEAD);

        EXTERNAL.resetAll();
        EXTERNAL.stubFor(post("/oauth2/token").willReturn(okJson(
                "{\"access_token\":\"service-token\",\"expires_in\":900}")));
        EXTERNAL.stubFor(get(urlPathMatching("/internal/v1/merchants/.*/webhook-config")).willReturn(okJson("""
                {"merchantId":"%s","merchantStatus":"ACTIVE","webhookUrl":"%s/hooks","webhookSecret":"%s"}"""
                .formatted(merchantId, EXTERNAL.baseUrl(), WEBHOOK_SECRET))));
        EXTERNAL.stubFor(post("/hooks").willReturn(ok()));

        asOperator();
        tx.executeWithoutResult(status -> admin.requeue(notificationId));
        dispatcher.dispatchDue();

        Notification delivered = notifications.findById(notificationId).orElseThrow();
        assertThat(delivered.getStatus()).isEqualTo(NotificationStatus.DELIVERED);
        // The history of what was attempted is kept, not rewritten.
        assertThat(dispatcher.attemptsOf(notificationId)).hasSize(4);
    }

    @Test
    void aNotificationLeftSendingByACrashIsPickedUpAgain() {
        EXTERNAL.stubFor(post("/hooks").willReturn(ok()));
        seedMerchant(2, EXTERNAL.baseUrl() + "/hooks", false, false);
        UUID notificationId = intake(UUID.randomUUID(), "PaymentCaptured").get(0).getId();

        // Exactly what a crash between claiming and recording leaves behind.
        jdbc.update("UPDATE notifications SET status = 'SENDING', next_attempt_at = NULL, "
                + "updated_at = now() - interval '10 minutes' WHERE id = ?", notificationId);
        // Not due: the row is SENDING, which the due query deliberately ignores.
        dispatcher.dispatchDue();
        assertThat(notifications.findById(notificationId).orElseThrow().getStatus())
                .isEqualTo(NotificationStatus.SENDING);

        assertThat(dispatcher.recoverStuck()).isEqualTo(1);
        assertThat(notifications.findById(notificationId).orElseThrow().getStatus())
                .isEqualTo(NotificationStatus.DELIVERED);
    }

    @Test
    void aMerchantWithNoWebhookUrlGetsNoWebhookButPatientsAreStillTold() {
        seedMerchant(2, null, true, false);
        List<Notification> created = intake(UUID.randomUUID(), "PaymentCaptured");
        assertThat(created).extracting(Notification::getChannel).containsExactly(Channel.EMAIL);
    }

    @Test
    void hospitalPreferencesDecideWhetherPatientsAreContacted() {
        seedMerchant(2, EXTERNAL.baseUrl() + "/hooks", false, false);
        assertThat(intake(UUID.randomUUID(), "PaymentCaptured")).extracting(Notification::getChannel)
                .containsExactly(Channel.WEBHOOK);
    }

    @Test
    void anAuthorizedPaymentIsReportedToTheHospitalOnly() {
        assertThat(intake(UUID.randomUUID(), "PaymentAuthorized")).extracting(Notification::getChannel)
                .containsExactly(Channel.WEBHOOK);
    }

    @Test
    void aFailedPaymentEmailsThePatientButSendsNoSms() {
        assertThat(intake(UUID.randomUUID(), "PaymentFailed")).extracting(Notification::getChannel)
                .containsExactlyInAnyOrder(Channel.WEBHOOK, Channel.EMAIL);
    }

    @Test
    void anEventForAnUnknownMerchantIsRetriedNotDropped() {
        UUID unknown = UUID.randomUUID();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        tx.execute(status -> intake.intake(UUID.randomUUID(), "PaymentCaptured", unknown, 1, payload())))
                .isInstanceOf(NotificationIntakeService.MerchantReplicaMissingException.class);
    }

    @Test
    void anOlderMerchantSnapshotIsIgnored() {
        seedMerchant(5, EXTERNAL.baseUrl() + "/new", true, true);
        seedMerchant(4, EXTERNAL.baseUrl() + "/old", false, false);
        assertThat(replicas.find(merchantId).orElseThrow().getWebhookUrl()).endsWith("/new");
        assertThat(replicas.find(merchantId).orElseThrow().getSnapshotVersion()).isEqualTo(5);
    }

    @Test
    void attemptHistoryCannotBeRewritten() {
        EXTERNAL.stubFor(post("/hooks").willReturn(ok()));
        UUID notificationId = intake(UUID.randomUUID(), "PaymentCaptured").get(0).getId();
        dispatcher.dispatchDue();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                        "DELETE FROM notification_attempts WHERE notification_id = ?", notificationId))
                .hasMessageContaining("append-only");
    }
}
