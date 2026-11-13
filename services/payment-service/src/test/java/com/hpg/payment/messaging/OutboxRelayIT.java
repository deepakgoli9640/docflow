package com.hpg.payment.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.common.money.Money;
import com.hpg.events.Topics;
import com.hpg.messaging.outbox.OutboxEventRepository;
import com.hpg.messaging.outbox.OutboxRelay;
import com.hpg.payment.domain.CaptureMode;
import com.hpg.payment.domain.MerchantConfigReplica;
import com.hpg.payment.infrastructure.persistence.MerchantConfigReplicaRepository;
import com.hpg.payment.support.KafkaTestContainer;
import com.hpg.payment.support.PostgresTestContainer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 * Outbox -> Kafka: events written with the business change are published in order, keyed by payment id,
 * with the envelope intact. The relay's background thread is off; the test drives it directly.
 */
@SpringBootTest(properties = {"hpg.payment.provider=simulated", "spring.kafka.admin.auto-create=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({PostgresTestContainer.class, KafkaTestContainer.class})
class OutboxRelayIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    MerchantConfigReplicaRepository replicas;
    @Autowired
    OutboxEventRepository outbox;
    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired
    org.springframework.kafka.core.KafkaTemplate<String, String> kafka;
    @Autowired
    KafkaConnectionDetails kafkaConnection;

    @Test
    void publishesPaymentEventsInOrderAndMarksThemPublished() throws Exception {
        UUID merchantId = UUID.randomUUID();
        MerchantConfigReplica r = new MerchantConfigReplica(merchantId);
        r.apply(new MerchantConfigReplica.Snapshot(1, "Demo Hospital", "ACTIVE", Set.of("UPI"), CaptureMode.AUTO,
                Money.inr("1.00"), Money.inr("200000.00"), Money.inr("1000000.00"), 60, 3), Instant.now());
        replicas.save(r);

        JsonNode p = json.readTree(mvc.perform(post("/api/v1/payments")
                        .with(jwt().jwt(j -> j.claim("mid", merchantId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_MERCHANT")))
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantReference\":\"RELAY-1\",\"amount\":{\"amount\":\"750.00\",\"currency\":\"INR\"}}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String paymentId = p.get("id").asText();
        String url = p.get("checkoutUrl").asText();
        mvc.perform(post("/checkout/{t}/simulate", url.substring(url.lastIndexOf('/') + 1)).param("outcome", "SUCCESS"))
                .andExpect(status().isOk());

        OutboxRelay relay = new OutboxRelay(jdbc,
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource())),
                kafka, new com.hpg.messaging.outbox.OutboxRelayProperties(false, Duration.ofSeconds(1), 100,
                        Duration.ofSeconds(20), Duration.ofDays(7)), java.time.Clock.systemUTC());
        await().atMost(Duration.ofSeconds(60)).until(() -> {
            relay.publishBatch();
            return outbox.countByPublishedAtIsNull() == 0;
        });

        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", kafkaConnection.getBootstrapServers()),
                ConsumerConfig.GROUP_ID_CONFIG, "relay-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"), new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(Topics.PAYMENT_EVENTS));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(rec -> {
                    if (paymentId.equals(rec.key())) {
                        records.add(rec);
                    }
                });
                return records.size() >= 2;
            });
        }
        assertThat(records).extracting(rec -> json.readTree(rec.value()).get("eventType").asText())
                .containsExactly("PaymentInitiated", "PaymentCaptured");
        JsonNode captured = json.readTree(records.get(1).value());
        assertThat(captured.get("aggregateId").asText()).isEqualTo(paymentId);
        assertThat(captured.at("/payload/amount/amount").asText()).isEqualTo("750.00");
        assertThat(captured.at("/payload/providerPaymentId").asText()).startsWith("pay_sim_");
        assertThat(new String(records.get(1).headers().lastHeader("eventType").value(), StandardCharsets.UTF_8))
                .isEqualTo("PaymentCaptured");
    }
}
