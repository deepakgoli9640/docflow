package com.hpg.notification.messaging;

import com.hpg.events.Topics;
import com.hpg.notification.application.MerchantReplicaService;
import com.hpg.notification.support.KafkaTestContainer;
import com.hpg.notification.support.PostgresTestContainer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The consumer side against a real broker: events become notification rows, a redelivery does not
 * produce a second message, and an event this service cannot read is dead-lettered instead of blocking
 * the partition behind it.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.admin.auto-create=true",
        "hpg.notification.allow-private-webhook-targets=true",
        // Fail fast in tests: one retry, then the dead-letter topic.
        "hpg.kafka.consumer.retry-attempts=2",
        "hpg.kafka.consumer.retry-initial=100ms",
        "hpg.kafka.consumer.retry-max=200ms"})
@ActiveProfiles("test")
@Import({PostgresTestContainer.class, KafkaTestContainer.class})
class EventConsumptionIT {

    @Autowired
    MerchantReplicaService replicas;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    KafkaConnectionDetails kafkaConnection;

    KafkaProducer<String, String> producer;
    UUID merchantId;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE notification_attempts, notifications, outbox_events");
        merchantId = UUID.randomUUID();
        producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaConnection.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()));
    }

    @AfterEach
    void tearDown() {
        producer.close();
    }

    private void publish(String topic, String key, String value) {
        try {
            producer.send(new ProducerRecord<>(topic, key, value)).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String merchantSnapshot(long version, String webhookUrl) {
        return """
                {"eventId":"%s","eventType":"MerchantSnapshot","eventVersion":1,"occurredAt":"%s",
                 "producer":"merchant-service","aggregateType":"Merchant","aggregateId":"%s","aggregateVersion":%d,
                 "merchantId":"%s","correlationId":"it-corr-1",
                 "payload":{"merchantId":"%s","version":%d,"legalName":"City Hospital Pvt Ltd",
                   "displayName":"City Hospital","status":"ACTIVE","enabledMethods":["UPI"],"captureMode":"AUTO",
                   "minAmount":{"amount":"1.00","currency":"INR"},
                   "maxAmountPerTransaction":{"amount":"200000.00","currency":"INR"},
                   "dailyLimit":{"amount":"1000000.00","currency":"INR"},"paymentTtlMinutes":60,"maxAttempts":3,
                   "refundWindowDays":180,"webhookUrl":%s,"notifyPatientEmail":true,"notifyPatientSms":false,
                   "updatedAt":"%s"}}"""
                .formatted(UUID.randomUUID(), Instant.now(), merchantId, version, merchantId, merchantId, version,
                        webhookUrl == null ? "null" : "\"" + webhookUrl + "\"", Instant.now());
    }

    private String paymentCaptured(UUID eventId, UUID paymentId) {
        return """
                {"eventId":"%s","eventType":"PaymentCaptured","eventVersion":1,"occurredAt":"%s",
                 "producer":"payment-service","aggregateType":"Payment","aggregateId":"%s","aggregateVersion":4,
                 "merchantId":"%s","correlationId":"his-req-77",
                 "payload":{"paymentId":"%s","merchantId":"%s","merchantReference":"BILL-77",
                   "amount":{"amount":"20000.00","currency":"INR"},"status":"CAPTURED","captureMode":"AUTO",
                   "customer":{"name":"A Patient","email":"patient@example.com","phone":"+919800001234"}}}"""
                .formatted(eventId, Instant.now(), paymentId, merchantId, paymentId, merchantId);
    }

    @Test
    void aMerchantSnapshotBuildsTheLocalReplica() {
        publish(Topics.MERCHANT_SNAPSHOTS, merchantId.toString(),
                merchantSnapshot(1, "https://his.example/hooks"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(replicas.find(merchantId)).isPresent()
                        .get().satisfies(replica -> {
                            assertThat(replica.getDisplayName()).isEqualTo("City Hospital");
                            assertThat(replica.getWebhookUrl()).isEqualTo("https://his.example/hooks");
                            assertThat(replica.isNotifyPatientEmail()).isTrue();
                        }));
    }

    @Test
    void aPaymentEventBecomesNotificationRowsAndARedeliveryAddsNothing() {
        publish(Topics.MERCHANT_SNAPSHOTS, merchantId.toString(), merchantSnapshot(1, "https://his.example/hooks"));
        await().atMost(Duration.ofSeconds(30)).until(() -> replicas.find(merchantId).isPresent());

        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        String event = paymentCaptured(eventId, paymentId);
        publish(Topics.PAYMENT_EVENTS, paymentId.toString(), event);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(channelsFor(eventId)).containsExactlyInAnyOrder("EMAIL", "WEBHOOK"));

        // Exactly what happens when the relay re-sends an event it already published, or a dead-letter
        // record is replayed: the same event id arrives again.
        publish(Topics.PAYMENT_EVENTS, paymentId.toString(), event);
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(channelsFor(eventId)).hasSize(2));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Long.class,
                eventId)).isEqualTo(1);
    }

    @Test
    void anUnreadableEventIsDeadLetteredRatherThanBlockingThePartition() {
        try (KafkaConsumer<String, String> dlt = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaConnection.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-watch-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()))) {
            dlt.subscribe(List.of(Topics.deadLetterOf(Topics.PAYMENT_EVENTS)));

            publish(Topics.PAYMENT_EVENTS, "poison", "{ this is not valid json");

            ConsumerRecord<String, String> dead = awaitOne(dlt);
            // The original bytes are preserved, so the record can be inspected and replayed as it arrived.
            assertThat(dead.value()).isEqualTo("{ this is not valid json");
            assertThat(header(dead, "hpg-dlt-original-topic")).isEqualTo(Topics.PAYMENT_EVENTS);
            assertThat(header(dead, "hpg-dlt-exception-type")).contains("Exception");
            assertThat(header(dead, "hpg-dlt-service")).isEqualTo("notification-service");

            // And the partition keeps moving: a good event after the poison one is still processed.
            publish(Topics.MERCHANT_SNAPSHOTS, merchantId.toString(), merchantSnapshot(1, null));
            UUID eventId = UUID.randomUUID();
            UUID paymentId = UUID.randomUUID();
            publish(Topics.PAYMENT_EVENTS, paymentId.toString(), paymentCaptured(eventId, paymentId));
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(channelsFor(eventId)).contains("EMAIL"));
        }
    }

    private List<String> channelsFor(UUID eventId) {
        return jdbc.queryForList("SELECT channel FROM notifications WHERE source_event_id = ? ORDER BY channel",
                String.class, eventId);
    }

    private static ConsumerRecord<String, String> awaitOne(KafkaConsumer<String, String> consumer) {
        long deadline = System.currentTimeMillis() + 40_000;
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                return record;
            }
        }
        throw new AssertionError("No dead-letter record arrived");
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
