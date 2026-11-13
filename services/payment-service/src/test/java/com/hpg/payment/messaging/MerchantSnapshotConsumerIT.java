package com.hpg.payment.messaging;

import com.hpg.events.Topics;
import com.hpg.payment.domain.CaptureMode;
import com.hpg.payment.domain.MerchantConfigReplica;
import com.hpg.payment.infrastructure.persistence.MerchantConfigReplicaRepository;
import com.hpg.payment.support.KafkaTestContainer;
import com.hpg.payment.support.PostgresTestContainer;
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
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * payment-service building its merchant replica from {@code merchant.snapshots.v1}.
 *
 * <p>This replaces the temporary admin endpoint that Phase 3 used to seed the replica by hand: nothing
 * copies merchant configuration into payment-service any more except merchant-service's own events.
 */
@SpringBootTest(properties = {
        "hpg.payment.provider=simulated",
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.admin.auto-create=true",
        "hpg.kafka.consumer.retry-attempts=2",
        "hpg.kafka.consumer.retry-initial=100ms"})
@ActiveProfiles("test")
@Import({PostgresTestContainer.class, KafkaTestContainer.class})
class MerchantSnapshotConsumerIT {

    @Autowired
    MerchantConfigReplicaRepository replicas;
    @Autowired
    KafkaConnectionDetails kafkaConnection;

    KafkaProducer<String, String> producer;
    UUID merchantId;

    @BeforeEach
    void setUp() {
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

    private void publish(String value) {
        publish(Topics.MERCHANT_SNAPSHOTS, merchantId.toString(), value);
    }

    private void publish(String topic, String key, String value) {
        try {
            producer.send(new ProducerRecord<>(topic, key, value)).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String snapshot(long version, int eventVersion, String status, String captureMode, String maxPerTxn) {
        return """
                {"eventId":"%s","eventType":"MerchantSnapshot","eventVersion":%d,"occurredAt":"%s",
                 "producer":"merchant-service","aggregateType":"Merchant","aggregateId":"%s","aggregateVersion":%d,
                 "merchantId":"%s","correlationId":"it-1",
                 "payload":{"merchantId":"%s","version":%d,"legalName":"City Hospital Pvt Ltd",
                   "displayName":"City Hospital","status":"%s","enabledMethods":["UPI","CARD"],
                   "captureMode":"%s","minAmount":{"amount":"1.00","currency":"INR"},
                   "maxAmountPerTransaction":{"amount":"%s","currency":"INR"},
                   "dailyLimit":{"amount":"1000000.00","currency":"INR"},"paymentTtlMinutes":45,"maxAttempts":2,
                   "refundWindowDays":180,"webhookUrl":"https://his.example/hooks","notifyPatientEmail":true,
                   "notifyPatientSms":false,"updatedAt":"%s"}}"""
                .formatted(UUID.randomUUID(), eventVersion, Instant.now(), merchantId, version, merchantId,
                        merchantId, version, status, captureMode, maxPerTxn, Instant.now());
    }

    @Test
    void aSnapshotCreatesTheReplicaPaymentCreationReadsFrom() {
        publish(snapshot(1, 1, "ACTIVE", "MANUAL", "150000.00"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            MerchantConfigReplica replica = replicas.findById(merchantId).orElse(null);
            assertThat(replica).isNotNull();
            assertThat(replica.isActive()).isTrue();
            assertThat(replica.getDisplayName()).isEqualTo("City Hospital");
            assertThat(replica.getCaptureMode()).isEqualTo(CaptureMode.MANUAL);
            assertThat(replica.maxAmountPerTxn().amount()).isEqualByComparingTo("150000.00");
            assertThat(replica.getPaymentTtlMinutes()).isEqualTo(45);
            assertThat(replica.getMaxAttempts()).isEqualTo(2);
            assertThat(replica.getEnabledMethods()).containsExactlyInAnyOrder("UPI", "CARD");
        });
    }

    @Test
    void anOlderSnapshotNeverMovesTheReplicaBackwards() {
        publish(snapshot(5, 1, "SUSPENDED", "AUTO", "9000.00"));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(replicas.findById(merchantId)).isPresent()
                        .get().extracting(MerchantConfigReplica::getSnapshotVersion).isEqualTo(5L));

        // A redelivery, or a replay of the compacted topic from the beginning, can present old versions.
        publish(snapshot(4, 1, "ACTIVE", "MANUAL", "150000.00"));
        publish(snapshot(3, 1, "ACTIVE", "MANUAL", "150000.00"));

        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            MerchantConfigReplica replica = replicas.findById(merchantId).orElseThrow();
            assertThat(replica.getSnapshotVersion()).isEqualTo(5L);
            assertThat(replica.getStatus()).isEqualTo("SUSPENDED");
        });
    }

    @Test
    void aSnapshotFromANewerProducerIsDeadLetteredRatherThanGuessedAt() {
        try (KafkaConsumer<String, String> dlt = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaConnection.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-watch-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()))) {
            dlt.subscribe(List.of(Topics.deadLetterOf(Topics.MERCHANT_SNAPSHOTS)));

            publish(snapshot(1, 99, "ACTIVE", "AUTO", "150000.00"));

            long deadline = System.currentTimeMillis() + 40_000;
            ConsumerRecord<String, String> dead = null;
            while (dead == null && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : dlt.poll(Duration.ofMillis(500))) {
                    if (merchantId.toString().equals(record.key())) {
                        dead = record;
                        break;
                    }
                }
            }
            assertThat(dead).as("snapshot with an unknown schema version is dead-lettered").isNotNull();
            // Applying a payload we do not understand could set wrong payment limits, so nothing is applied.
            assertThat(replicas.findById(merchantId)).isEmpty();
        }
    }
}
