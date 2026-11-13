package com.hpg.payment.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.common.money.Money;
import com.hpg.events.EventHeaders;
import com.hpg.events.Topics;
import com.hpg.messaging.outbox.OutboxRelay;
import com.hpg.payment.application.CreatePaymentCommand;
import com.hpg.payment.application.PaymentCreationService;
import com.hpg.payment.domain.CaptureMode;
import com.hpg.payment.domain.MerchantConfigReplica;
import com.hpg.payment.infrastructure.persistence.MerchantConfigReplicaRepository;
import com.hpg.payment.support.KafkaTestContainer;
import com.hpg.payment.support.PostgresTestContainer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The transactional outbox against a real broker: a committed payment produces exactly the events a
 * consumer should see, in the order they happened.
 */
@SpringBootTest(properties = {"hpg.payment.provider=simulated", "spring.kafka.admin.auto-create=true"})
@ActiveProfiles("test")
@Import({PostgresTestContainer.class, KafkaTestContainer.class})
class OutboxRelayIT {

    @Autowired
    PaymentCreationService creation;
    @Autowired
    MerchantConfigReplicaRepository replicas;
    @Autowired
    OutboxRelay relay;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;
    @Autowired
    org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails kafkaConnection;

    UUID merchantId;
    KafkaConsumer<String, String> consumer;

    @BeforeEach
    void setUp() {
        merchantId = UUID.randomUUID();
        MerchantConfigReplica replica = new MerchantConfigReplica(merchantId);
        replica.apply(new MerchantConfigReplica.Snapshot(1, "City Hospital", "ACTIVE", Set.of("UPI"),
                CaptureMode.AUTO, Money.inr("1.00"), Money.inr("200000.00"), Money.inr("1000000.00"), 60, 3),
                Instant.now());
        replicas.save(replica);
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaConnection.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));
        consumer.subscribe(List.of(Topics.PAYMENT_EVENTS));
    }

    @AfterEach
    void tearDown() {
        consumer.close();
        SecurityContextHolder.clearContext();
    }

    private UUID createPayment(String reference) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("hpgk_test")
                .claim("mid", merchantId.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT"))));
        return creation.create(new CreatePaymentCommand(merchantId, reference, Money.inr("20000.00"), null, null,
                null, null), UUID.randomUUID().toString(), Map.of("ref", reference)).payment().getId();
    }

    /** Reads the records for one payment. The broker is shared by the tests in this class, so records are
     *  selected by key rather than by assuming the topic is empty. */
    private List<ConsumerRecord<String, String>> poll(UUID paymentId, int expected) {
        List<ConsumerRecord<String, String>> collected = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 20_000;
        while (collected.size() < expected && System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                if (paymentId.toString().equals(record.key())) {
                    collected.add(record);
                }
            }
        }
        return collected;
    }

    @Test
    void publishesCommittedEventsWithKeyHeadersAndEnvelope() throws Exception {
        UUID paymentId = createPayment("BILL-" + UUID.randomUUID().toString().substring(0, 8));
        assertThat(relay.publishBatch()).isPositive();

        List<ConsumerRecord<String, String>> records = poll(paymentId, 1);
        assertThat(records).hasSize(1);
        ConsumerRecord<String, String> record = records.get(0);
        // Keyed by payment id: every event for one payment lands on the same partition, so a consumer
        // sees them in the order they happened.
        assertThat(record.key()).isEqualTo(paymentId.toString());
        assertThat(new String(record.headers().lastHeader(EventHeaders.EVENT_TYPE).value(), StandardCharsets.UTF_8))
                .isEqualTo("PaymentInitiated");

        JsonNode envelope = json.readTree(record.value());
        assertThat(envelope.get("eventType").asText()).isEqualTo("PaymentInitiated");
        assertThat(envelope.get("producer").asText()).isEqualTo("payment-service");
        assertThat(envelope.get("merchantId").asText()).isEqualTo(merchantId.toString());
        assertThat(envelope.at("/payload/amount/amount").asText()).isEqualTo("20000.00");
        assertThat(envelope.at("/payload/paymentId").asText()).isEqualTo(paymentId.toString());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Long.class))
                .isZero();
    }

    @Test
    void publishesOnePaymentsEventsInTheOrderTheyHappened() {
        UUID paymentId = createPayment("BILL-" + UUID.randomUUID().toString().substring(0, 8));
        jdbc.update("UPDATE payments SET status = 'CAPTURED', captured_at = now() WHERE id = ?", paymentId);
        // Two more events for the same payment, appended after the first.
        jdbc.update("""
                INSERT INTO outbox_events (event_id, aggregate_type, aggregate_id, event_type, topic, message_key,
                                           payload, created_at)
                VALUES (gen_random_uuid(), 'Payment', ?, 'PaymentAuthorized', ?, ?, ?::jsonb, now()),
                       (gen_random_uuid(), 'Payment', ?, 'PaymentCaptured', ?, ?, ?::jsonb, now())""",
                paymentId.toString(), Topics.PAYMENT_EVENTS, paymentId.toString(),
                "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"PaymentAuthorized\",\"eventVersion\":1,"
                        + "\"occurredAt\":\"2026-11-12T10:00:00Z\",\"aggregateId\":\"" + paymentId + "\",\"payload\":{}}",
                paymentId.toString(), Topics.PAYMENT_EVENTS, paymentId.toString(),
                "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"PaymentCaptured\",\"eventVersion\":1,"
                        + "\"occurredAt\":\"2026-11-12T10:00:01Z\",\"aggregateId\":\"" + paymentId + "\",\"payload\":{}}");

        relay.publishBatch();

        List<String> types = poll(paymentId, 3).stream()
                .map(r -> {
                    try {
                        return json.readTree(r.value()).get("eventType").asText();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .toList();
        assertThat(types).containsExactly("PaymentInitiated", "PaymentAuthorized", "PaymentCaptured");
    }

    @Test
    void publishedRowsAreCleanedUpButUnpublishedOnesAreKept() {
        createPayment("BILL-" + UUID.randomUUID().toString().substring(0, 8));
        relay.publishBatch();
        jdbc.update("UPDATE outbox_events SET published_at = now() - interval '30 days'");
        createPayment("BILL-" + UUID.randomUUID().toString().substring(0, 8));

        assertThat(relay.deletePublishedOlderThan(Duration.ofDays(7))).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Long.class)).isEqualTo(1);
    }
}
