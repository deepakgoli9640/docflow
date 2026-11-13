package com.hpg.merchant.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.events.Topics;
import com.hpg.merchant.support.ApiTestSupport;
import com.hpg.merchant.support.KafkaTestContainer;
import com.hpg.merchant.support.PostgresTestContainer;
import com.hpg.messaging.outbox.OutboxRelay;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.hpg.merchant.support.ApiTestSupport.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * merchant-service publishing merchant snapshots to a real broker.
 *
 * <p>What matters here is that the snapshot carries everything other services need, that it never carries
 * the signing secret, and that a configuration change produces a newer version.
 */
@SpringBootTest(properties = {"spring.kafka.admin.auto-create=true", "hpg.merchant.allow-insecure-webhooks=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({PostgresTestContainer.class, KafkaTestContainer.class})
class MerchantSnapshotPublishingIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    OutboxRelay relay;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    KafkaConnectionDetails kafkaConnection;

    ApiTestSupport api;
    KafkaConsumer<String, String> consumer;

    @BeforeEach
    void setUp() {
        api = new ApiTestSupport(mvc, json);
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaConnection.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));
        consumer.subscribe(List.of(Topics.MERCHANT_SNAPSHOTS));
    }

    @AfterEach
    void tearDown() {
        consumer.close();
    }

    private List<JsonNode> snapshotsFor(UUID merchantId, int expected) {
        List<JsonNode> collected = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 20_000;
        while (collected.size() < expected && System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                if (merchantId.toString().equals(record.key())) {
                    try {
                        collected.add(json.readTree(record.value()));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
        }
        return collected;
    }

    @Test
    void registrationPublishesASnapshotKeyedByMerchantWithoutTheSigningSecret() throws Exception {
        var merchant = api.register("snapshot+" + UUID.randomUUID() + "@hospital.example");
        assertThat(relay.publishBatch()).isPositive();

        List<JsonNode> snapshots = snapshotsFor(merchant.id(), 1);
        assertThat(snapshots).hasSize(1);
        JsonNode envelope = snapshots.get(0);
        assertThat(envelope.get("eventType").asText()).isEqualTo("MerchantSnapshot");
        assertThat(envelope.get("producer").asText()).isEqualTo("merchant-service");
        JsonNode payload = envelope.get("payload");
        assertThat(payload.get("status").asText()).isEqualTo("PENDING_ACTIVATION");
        assertThat(payload.get("displayName").asText()).isEqualTo("City Hospital");
        assertThat(payload.get("version").asLong()).isEqualTo(1);
        assertThat(payload.get("maxAmountPerTransaction").get("amount").asText()).isEqualTo("200000.00");
        assertThat(payload.get("paymentTtlMinutes").asInt()).isEqualTo(60);
        // The signing secret must never travel on a retained topic.
        assertThat(envelope.toString()).doesNotContain(merchant.webhookSecret()).doesNotContain("webhookSecret");
    }

    @Test
    void activationAndConfigurationChangesPublishNewerVersions() throws Exception {
        var merchant = api.register("snapshot+" + UUID.randomUUID() + "@hospital.example");
        String ops = api.opsToken();
        String token = api.token(merchant.keyId(), merchant.keySecret());

        mvc.perform(post("/api/v1/admin/merchants/{id}/activate", merchant.id())
                        .header("Authorization", bearer(ops)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"KYC verified\"}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/merchants/me/configuration").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"enabledMethods":["UPI"],"captureMode":"MANUAL",
                         "minAmount":{"amount":"10.00","currency":"INR"},
                         "maxAmountPerTransaction":{"amount":"150000.00","currency":"INR"},
                         "dailyLimit":{"amount":"2000000.00","currency":"INR"},
                         "paymentTtlMinutes":30,"maxAttempts":2,"refundWindowDays":90,
                         "webhookUrl":"https://his.cityhospital.example/hpg/webhooks",
                         "notifyPatientEmail":true,"notifyPatientSms":true}"""))
                .andExpect(status().isOk());
        relay.publishBatch();

        List<JsonNode> snapshots = snapshotsFor(merchant.id(), 3);
        assertThat(snapshots).hasSize(3);
        assertThat(snapshots).extracting(s -> s.at("/payload/version").asLong()).containsExactly(1L, 2L, 3L);
        assertThat(snapshots).extracting(s -> s.at("/payload/status").asText())
                .containsExactly("PENDING_ACTIVATION", "ACTIVE", "ACTIVE");
        JsonNode latest = snapshots.get(2).get("payload");
        assertThat(latest.get("captureMode").asText()).isEqualTo("MANUAL");
        assertThat(latest.get("webhookUrl").asText()).isEqualTo("https://his.cityhospital.example/hpg/webhooks");
        assertThat(latest.get("notifyPatientSms").asBoolean()).isTrue();
        assertThat(latest.get("maxAttempts").asInt()).isEqualTo(2);
    }

    @Test
    void suspensionPublishesAStatusOtherServicesCanActOn() throws Exception {
        var merchant = api.register("snapshot+" + UUID.randomUUID() + "@hospital.example");
        String ops = api.opsToken();
        mvc.perform(post("/api/v1/admin/merchants/{id}/activate", merchant.id())
                .header("Authorization", bearer(ops)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"KYC verified\"}"));
        mvc.perform(post("/api/v1/admin/merchants/{id}/suspend", merchant.id())
                        .header("Authorization", bearer(ops)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Chargeback investigation\"}"))
                .andExpect(status().isOk());
        relay.publishBatch();

        List<JsonNode> snapshots = snapshotsFor(merchant.id(), 3);
        assertThat(snapshots.get(snapshots.size() - 1).at("/payload/status").asText()).isEqualTo("SUSPENDED");
    }

    @Test
    void theSnapshotIsWrittenInTheSameTransactionAsTheChange() throws Exception {
        var merchant = api.register("snapshot+" + UUID.randomUUID() + "@hospital.example");
        // Before the relay runs, the event is already committed in the outbox next to the merchant row.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND published_at IS NULL",
                Long.class, merchant.id().toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT snapshot_version FROM merchants WHERE id = ?", Long.class,
                merchant.id())).isEqualTo(1);
    }
}
