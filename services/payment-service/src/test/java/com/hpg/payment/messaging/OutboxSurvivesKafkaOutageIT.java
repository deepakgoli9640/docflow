package com.hpg.payment.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.common.money.Money;
import com.hpg.events.Topics;
import com.hpg.messaging.outbox.OutboxEventRepository;
import com.hpg.messaging.outbox.OutboxRelay;
import com.hpg.messaging.outbox.OutboxRelayProperties;
import com.hpg.payment.domain.CaptureMode;
import com.hpg.payment.domain.MerchantConfigReplica;
import com.hpg.payment.infrastructure.persistence.MerchantConfigReplicaRepository;
import com.hpg.payment.support.KafkaTestContainer;
import com.hpg.payment.support.PostgresTestContainer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Failure scenario 6 (docs/11-failure-scenarios.md): Kafka is down. The API keeps accepting payments because it
 * only writes to Postgres (the outbox); the relay fails without losing or reordering anything, and once Kafka is
 * back every event is delivered, in order per payment.
 */
@SpringBootTest(properties = {"hpg.payment.provider=simulated", "spring.kafka.admin.auto-create=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({PostgresTestContainer.class, KafkaTestContainer.class})
class OutboxSurvivesKafkaOutageIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    MerchantConfigReplicaRepository replicas;
    @Autowired
    OutboxEventRepository outbox;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    KafkaConnectionDetails kafkaConnection;
    @Autowired
    KafkaContainer kafkaContainer;

    @Test
    void paymentsAreAcceptedWhileKafkaIsDownAndEveryEventIsDeliveredInOrderAfterwards() throws Exception {
        UUID merchantId = UUID.randomUUID();
        MerchantConfigReplica r = new MerchantConfigReplica(merchantId);
        r.apply(new MerchantConfigReplica.Snapshot(1, "Outage Hospital", "ACTIVE", Set.of("UPI"), CaptureMode.AUTO,
                Money.inr("1.00"), Money.inr("200000.00"), Money.inr("1000000.00"), 60, 3), Instant.now());
        replicas.save(r);
        OutboxRelay relay = new OutboxRelay(jdbc, new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
                kafka, new OutboxRelayProperties(false, Duration.ofSeconds(1), 100, Duration.ofSeconds(3),
                        Duration.ofDays(7)), Clock.systemUTC());

        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(kafkaContainer.getContainerId()).exec();
        List<String> paymentIds = new ArrayList<>();
        try {
            for (int i = 1; i <= 3; i++) {
                JsonNode p = json.readTree(mvc.perform(post("/api/v1/payments")
                                .with(jwt().jwt(j -> j.claim("mid", merchantId.toString()))
                                        .authorities(new SimpleGrantedAuthority("ROLE_MERCHANT")))
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"merchantReference\":\"OUTAGE-" + i + "\",\"amount\":{\"amount\":\"100.00\",\"currency\":\"INR\"}}"))
                        .andExpect(status().isCreated()) // the API does not depend on Kafka
                        .andReturn().getResponse().getContentAsString());
                paymentIds.add(p.get("id").asText());
                if (i == 1) { // a second event for the first payment, to check per-payment order
                    String url = p.get("checkoutUrl").asText();
                    mvc.perform(post("/checkout/{t}/simulate", url.substring(url.lastIndexOf('/') + 1))
                            .param("outcome", "SUCCESS")).andExpect(status().isOk());
                }
            }
            long pending = outbox.countByPublishedAtIsNull();
            assertThat(pending).isGreaterThanOrEqualTo(4);

            assertThat(relay.publishBatch()).isZero();
            // Nothing is marked published, and the failure is recorded on the first row only (the batch stops).
            assertThat(outbox.countByPublishedAtIsNull()).isEqualTo(pending);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM outbox_events WHERE published_at IS NULL AND last_error IS NOT NULL",
                    Integer.class)).isEqualTo(1);
        } finally {
            docker.unpauseContainerCmd(kafkaContainer.getContainerId()).exec();
        }

        await().atMost(Duration.ofSeconds(90)).until(() -> {
            relay.publishBatch();
            return outbox.countByPublishedAtIsNull() == 0;
        });

        // At least once: a send that timed out during the outage may still have reached Kafka, so a consumer can
        // see an event twice (it deduplicates by eventId). What must hold: nothing missing, order kept per key.
        Map<String, List<String>> typesByPayment = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", kafkaConnection.getBootstrapServers()),
                ConsumerConfig.GROUP_ID_CONFIG, "outage-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"), new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(Topics.PAYMENT_EVENTS));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(rec -> {
                    if (!paymentIds.contains(rec.key())) {
                        return;
                    }
                    JsonNode env = readTree(rec.value());
                    if (seen.add(env.get("eventId").asText())) {
                        typesByPayment.computeIfAbsent(rec.key(), k -> new ArrayList<>()).add(env.get("eventType").asText());
                    }
                });
                return typesByPayment.size() == 3 && typesByPayment.get(paymentIds.get(0)).size() >= 2;
            });
        }
        assertThat(typesByPayment.get(paymentIds.get(0))).containsExactly("PaymentInitiated", "PaymentCaptured");
        assertThat(typesByPayment.get(paymentIds.get(1))).containsExactly("PaymentInitiated");
        assertThat(typesByPayment.get(paymentIds.get(2))).containsExactly("PaymentInitiated");
    }

    private JsonNode readTree(String value) {
        try {
            return json.readTree(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
