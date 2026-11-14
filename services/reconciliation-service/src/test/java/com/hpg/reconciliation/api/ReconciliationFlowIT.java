package com.hpg.reconciliation.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.common.id.Ids;
import com.hpg.common.money.Money;
import com.hpg.events.EventEnvelope;
import com.hpg.events.Topics;
import com.hpg.events.ledger.TransactionCreatedPayload;
import com.hpg.messaging.outbox.OutboxEventRepository;
import com.hpg.reconciliation.support.KafkaTestContainer;
import com.hpg.reconciliation.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Ledger events in through Kafka, a settlement CSV in through the API, every match rule and the workflow out. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({PostgresTestContainer.class, KafkaTestContainer.class})
class ReconciliationFlowIT {

    static final String HEADER = "entity_id,type,order_id,amount,fee,tax,currency,settlement_id,settled_at,created_at\n";

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    OutboxEventRepository outbox;

    final String run = UUID.randomUUID().toString().substring(0, 6);   // unique provider references per test
    final LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    RequestPostProcessor ops() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_OPS"));
    }

    String ref(String name) {
        return name + "_" + run;
    }

    void booked(String type, String providerRef, String amount, Instant postedAt) throws Exception {
        UUID paymentId = Ids.newId();
        var payload = new TransactionCreatedPayload("TXN-" + providerRef, Ids.newId(), UUID.randomUUID(), type, paymentId,
                "REFUND".equals(type) ? Ids.newId() : null, "RAZORPAY", providerRef, Money.inr(amount), postedAt);
        var envelope = new EventEnvelope<>(Ids.newId(), "TransactionCreated", 1, postedAt, "transaction-service",
                "Transaction", payload.transactionId().toString(), 1, payload.merchantId(), null, null, payload);
        String value = json.writeValueAsString(envelope);
        kafka.send(Topics.LEDGER_EVENTS, paymentId.toString(), value).get();
        kafka.send(Topics.LEDGER_EVENTS, paymentId.toString(), value).get(); // redelivery is harmless
    }

    JsonNode upload(String csv, LocalDate date, int expectedStatus) throws Exception {
        String body = mvc.perform(multipart("/api/v1/reconciliation/settlement-files")
                        .file(new MockMultipartFile("file", "settlement-" + date + ".csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .param("businessDate", date.toString()).with(ops()))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    JsonNode awaitCompleted(String batchId) throws Exception {
        await().atMost(Duration.ofSeconds(30)).until(() -> !"RUNNING".equals(json.readTree(mvc.perform(
                get("/api/v1/reconciliation/batches/{id}", batchId).with(ops())).andReturn().getResponse().getContentAsString())
                .get("status").asText()));
        return json.readTree(mvc.perform(get("/api/v1/reconciliation/batches/{id}", batchId).with(ops()))
                .andReturn().getResponse().getContentAsString());
    }

    String recordId(String batchId, String status) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/reconciliation/batches/{id}/records", batchId).param("status", status).with(ops()))
                .andReturn().getResponse().getContentAsString()).at("/content/0/id").asText();
    }

    @Test
    void reconcilesEveryCaseAndRunsTheDiscrepancyWorkflow() throws Exception {
        Instant now = Instant.now();
        booked("PAYMENT", ref("pay_A"), "1000.00", now);                        // matched
        booked("REFUND", ref("rfnd_A"), "200.00", now);                         // matched (refund)
        booked("PAYMENT", ref("pay_B"), "500.00", now);                         // provider says 450.00
        booked("PAYMENT", ref("pay_C"), "300.00", now.minus(Duration.ofDays(3))); // overdue, provider never settled
        booked("PAYMENT", ref("pay_D"), "700.00", now);                         // not in report, still inside the window
        booked("PAYMENT", ref("pay_E"), "50.00", now);                          // provider reports it twice
        await().atMost(Duration.ofSeconds(60)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM internal_transactions WHERE provider_reference LIKE ?", Integer.class, "%_" + run) == 6);

        String csv = HEADER
                + ref("pay_A") + ",payment,order_1,1000.00,20.00,3.60,INR,setl_1,2026-11-13T10:30:00Z,2026-11-12T09:15:02Z\n"
                + ref("rfnd_A") + ",refund,,200,0,0,INR,setl_1,2026-11-13T10:30:00Z,2026-11-12T10:00:00Z\n"
                + ref("pay_B") + ",payment,order_2,450.00,9.00,1.62,INR,setl_1,2026-11-13T10:30:00Z,2026-11-12T11:00:00Z\n"
                + ref("pay_X") + ",payment,order_3,100.00,2.00,0.36,INR,setl_1,2026-11-13T10:30:00Z,2026-11-12T12:00:00Z\n"
                + ref("pay_E") + ",payment,order_4,50.00,1.00,0.18,INR,setl_1,2026-11-13T10:30:00Z,2026-11-12T13:00:00Z\n"
                + ref("pay_E") + ",payment,order_4,50.00,1.00,0.18,INR,setl_1,2026-11-13T10:30:00Z,2026-11-12T13:00:00Z\n"
                + "pay_bad,payment,,not-a-number,,,INR,,,\n";
        JsonNode started = upload(csv, today, 202);
        assertThat(started.get("status").asText()).isEqualTo("RUNNING");
        String batchId = started.get("id").asText();

        JsonNode batch = awaitCompleted(batchId);
        assertThat(batch.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(batch.get("providerRows").asInt()).isEqualTo(7);
        assertThat(batch.get("malformedRows").asInt()).isEqualTo(1);
        assertThat(batch.get("matched").asInt()).isEqualTo(2);
        assertThat(batch.get("mismatched").asInt()).isEqualTo(1);
        assertThat(batch.get("missingInInternalSystem").asInt()).isEqualTo(1);
        assertThat(batch.get("duplicate").asInt()).isEqualTo(2);
        assertThat(batch.get("pendingReview").asInt()).isEqualTo(1);
        // pay_C is overdue; earlier test data in the same database may add more overdue items, never fewer.
        assertThat(batch.get("missingInProvider").asInt()).isGreaterThanOrEqualTo(1);
        mvc.perform(get("/api/v1/reconciliation/batches/{id}/records", batchId).param("status", "MISSING_IN_PROVIDER")
                        .param("size", "100").with(ops()))
                .andExpect(jsonPath("$.content[?(@.providerReference == '" + ref("pay_C") + "')]").exists())
                .andExpect(jsonPath("$.content[?(@.providerReference == '" + ref("pay_D") + "')]").doesNotExist());

        // Same file again: rejected.
        assertThat(upload(csv, today, 409).get("errorCode").asText()).isEqualTo("DUPLICATE_SETTLEMENT_FILE");

        // Discrepancy workflow on the mismatch.
        String mismatch = recordId(batchId, "MISMATCHED");
        mvc.perform(get("/api/v1/reconciliation/records/{id}", mismatch).with(ops()))
                .andExpect(jsonPath("$.providerAmount.amount").value("450.00"))
                .andExpect(jsonPath("$.internalAmount.amount").value("500.00"))
                .andExpect(jsonPath("$.resolutionStatus").value("OPEN"));
        mvc.perform(post("/api/v1/reconciliation/records/{id}/review", mismatch).with(ops()))
                .andExpect(jsonPath("$.resolutionStatus").value("IN_REVIEW"));
        mvc.perform(post("/api/v1/reconciliation/records/{id}/resolve", mismatch).with(ops()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolutionType\":\"INTERNAL_CORRECTION_POSTED\",\"note\":\"Reversal posted\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/reconciliation/records/{id}/resolve", mismatch).with(ops()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolutionType\":\"PROVIDER_ERROR_RAISED\",\"note\":\"Ticket RZP-4411: provider short-settled\"}"))
                .andExpect(jsonPath("$.resolutionStatus").value("RESOLVED"))
                .andExpect(jsonPath("$.resolutionType").value("PROVIDER_ERROR_RAISED"))
                .andExpect(jsonPath("$.resolvedBy").exists());
        mvc.perform(post("/api/v1/reconciliation/records/{id}/resolve", recordId(batchId, "MATCHED")).with(ops())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"resolutionType\":\"ACCEPTED_AS_IS\",\"note\":\"x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("NOT_A_DISCREPANCY"));

        // Daily report as JSON and CSV.
        mvc.perform(get("/api/v1/reconciliation/reports/daily").param("date", today.toString()).with(ops()))
                .andExpect(jsonPath("$.batch.id").value(batchId))
                .andExpect(jsonPath("$.records.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(8)));
        mvc.perform(get("/api/v1/reconciliation/reports/daily").param("date", today.toString()).accept("text/csv").with(ops()))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("reconciliation-" + today)))
                .andExpect(content().string(org.hamcrest.Matchers.startsWith("status,entity_type,provider_reference")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("MISMATCHED,payment," + ref("pay_B"))));

        // A completed run publishes ReconciliationCompleted.
        assertThat(outbox.findByAggregateIdOrderByIdAsc(batchId)).singleElement().satisfies(e -> {
            assertThat(e.getEventType()).isEqualTo("ReconciliationCompleted");
            assertThat(e.getPayload().at("/payload/mismatched").asInt()).isEqualTo(1);
        });

        // Matched payments (only those) are confirmed to the ledger with their fees, for the settlement posting.
        var settled = outbox.findAll().stream().filter(e -> "SettlementConfirmed".equals(e.getEventType()))
                .filter(e -> e.getPayload().at("/payload/batchId").asText().equals(batchId)).toList();
        assertThat(settled).extracting(e -> e.getPayload().at("/payload/providerReference").asText())
                .contains(ref("pay_A")).doesNotContain(ref("pay_B"), ref("pay_X"));
        assertThat(settled).filteredOn(e -> e.getPayload().at("/payload/providerReference").asText().equals(ref("pay_A")))
                .singleElement().satisfies(e -> {
                    assertThat(e.getPayload().at("/payload/gross/amount").asText()).isEqualTo("1000.00");
                    assertThat(e.getPayload().at("/payload/fee/amount").asText()).isEqualTo("20.00");
                    assertThat(e.getPayload().at("/payload/tax/amount").asText()).isEqualTo("3.60");
                });

        // The same payment settled again in another day's report is a duplicate settlement.
        JsonNode other = awaitCompleted(upload(HEADER + ref("pay_A") + ",payment,order_1,1000.00,0,0,INR,setl_2,,\n",
                today.minusDays(1), 202).get("id").asText());
        assertThat(other.get("duplicate").asInt()).isEqualTo(1);
    }

    @Test
    void validatesInputsAndRoles() throws Exception {
        assertThat(upload("id,amount\n1,2\n", today, 422).get("errorCode").asText()).isEqualTo("INVALID_SETTLEMENT_FILE");
        assertThat(upload(HEADER, today.plusDays(2), 422).get("errorCode").asText()).isEqualTo("INVALID_BUSINESS_DATE");
        mvc.perform(post("/api/v1/reconciliation/runs").with(ops()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessDate\":\"" + today.minusDays(1) + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("SOURCE_UNAVAILABLE"));
        mvc.perform(get("/api/v1/reconciliation/batches")
                        .with(jwt().jwt(j -> j.claim("mid", UUID.randomUUID().toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_MERCHANT"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/reconciliation/reports/daily").param("date", "2020-01-01").with(ops()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NO_COMPLETED_BATCH"));
    }
}
