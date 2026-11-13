package com.hpg.merchant.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.events.Topics;
import com.hpg.merchant.application.MerchantSnapshotBackfill;
import com.hpg.merchant.support.ApiTestSupport;
import com.hpg.merchant.support.PostgresTestContainer;
import com.hpg.messaging.outbox.OutboxEvent;
import com.hpg.messaging.outbox.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static com.hpg.merchant.support.ApiTestSupport.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Every merchant change writes a full snapshot to the outbox, in the same transaction, with a rising version. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class MerchantSnapshotIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    OutboxEventRepository outbox;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MerchantSnapshotBackfill backfill;

    ApiTestSupport api;

    @BeforeEach
    void setUp() {
        api = new ApiTestSupport(mvc, json);
    }

    List<JsonNode> snapshots(UUID merchantId) {
        List<OutboxEvent> events = outbox.findByAggregateIdOrderByIdAsc(merchantId.toString());
        assertThat(events).allSatisfy(e -> {
            assertThat(e.getTopic()).isEqualTo(Topics.MERCHANT_SNAPSHOTS);
            assertThat(e.getEventType()).isEqualTo("MerchantSnapshot");
            assertThat(e.getMessageKey()).isEqualTo(merchantId.toString());
        });
        return events.stream().map(OutboxEvent::getPayload).toList();
    }

    @Test
    void registrationActivationProfileAndConfigurationEachPublishASnapshot() throws Exception {
        var m = api.register("billing+" + UUID.randomUUID() + "@hospital.example");
        String ops = api.opsToken();
        mvc.perform(post("/api/v1/admin/merchants/{id}/activate", m.id()).header(HttpHeaders.AUTHORIZATION, bearer(ops))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"KYC ok\"}"))
                .andExpect(status().isOk());
        String token = api.token(m.keyId(), m.keySecret());
        mvc.perform(patch("/api/v1/merchants/me").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"City Hospital East\"}"))
                .andExpect(status().isOk());
        String config = mvc.perform(get("/api/v1/merchants/me/configuration").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andReturn().getResponse().getContentAsString();
        JsonNode c = json.readTree(config);
        ((com.fasterxml.jackson.databind.node.ObjectNode) c).put("captureMode", "MANUAL");
        ((com.fasterxml.jackson.databind.node.ObjectNode) c).put("refundWindowDays", 30);
        mvc.perform(put("/api/v1/merchants/me/configuration").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(c.toString()))
                .andExpect(status().isOk());

        List<JsonNode> s = snapshots(m.id());
        assertThat(s).hasSize(4);
        assertThat(s).extracting(e -> e.get("aggregateVersion").asLong()).containsExactly(1L, 2L, 3L, 4L);
        assertThat(s).extracting(e -> e.at("/payload/status").asText())
                .containsExactly("PENDING_ACTIVATION", "ACTIVE", "ACTIVE", "ACTIVE");
        JsonNode last = s.getLast().get("payload");
        assertThat(last.get("snapshotVersion").asLong()).isEqualTo(4);
        assertThat(last.get("displayName").asText()).isEqualTo("City Hospital East");
        assertThat(last.get("captureMode").asText()).isEqualTo("MANUAL");
        assertThat(last.get("refundWindowDays").asInt()).isEqualTo(30);
        assertThat(last.at("/minAmount/currency").asText()).isEqualTo("INR");
        // Never secrets or contact details.
        String raw = s.toString();
        assertThat(raw).doesNotContain(m.webhookSecret()).doesNotContain(m.keySecret()).doesNotContain("@hospital.example");
    }

    @Test
    void backfillPublishesMerchantsThatNeverHadASnapshotAndOpsCanRepublishAll() throws Exception {
        var m = api.register("billing+" + UUID.randomUUID() + "@hospital.example");
        // Simulate a merchant created before snapshots existed.
        jdbc.update("UPDATE merchants SET snapshot_version = 0 WHERE id = ?", m.id());
        jdbc.update("DELETE FROM outbox_events WHERE aggregate_id = ?", m.id().toString());

        backfill.publishMissing();
        assertThat(snapshots(m.id())).extracting(e -> e.at("/payload/snapshotVersion").asLong()).containsExactly(1L);

        mvc.perform(post("/api/v1/admin/merchants/snapshots/republish").header(HttpHeaders.AUTHORIZATION, bearer(api.opsToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published").isNumber());
        assertThat(snapshots(m.id())).extracting(e -> e.at("/payload/snapshotVersion").asLong()).containsExactly(1L, 2L);

        String merchantToken = api.token(m.keyId(), m.keySecret());
        mvc.perform(post("/api/v1/admin/merchants/snapshots/republish").header(HttpHeaders.AUTHORIZATION, bearer(merchantToken)))
                .andExpect(status().isForbidden());
    }
}
