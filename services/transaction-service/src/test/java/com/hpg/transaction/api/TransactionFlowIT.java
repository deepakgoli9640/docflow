package com.hpg.transaction.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.common.id.Ids;
import com.hpg.common.money.Money;
import com.hpg.events.EventEnvelope;
import com.hpg.events.Topics;
import com.hpg.events.payment.PaymentEventPayload;
import com.hpg.events.refund.RefundEventPayload;
import com.hpg.transaction.support.KafkaTestContainer;
import com.hpg.transaction.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Events in through Kafka; transaction history, ledger and reports out through the API. */
@SpringBootTest(properties = "hpg.messaging.outbox.relay.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({PostgresTestContainer.class, KafkaTestContainer.class})
class TransactionFlowIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    com.hpg.messaging.outbox.OutboxEventRepository outbox;

    final UUID merchantId = UUID.randomUUID();
    final Instant t0 = Instant.now().minusSeconds(600);

    RequestPostProcessor merchant(UUID id) {
        return jwt().jwt(j -> j.claim("mid", id.toString())).authorities(new SimpleGrantedAuthority("ROLE_MERCHANT"));
    }

    RequestPostProcessor ops() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_OPS"));
    }

    void payment(UUID paymentId, String type, String status, long version, Instant at) throws Exception {
        var payload = new PaymentEventPayload(paymentId, merchantId, "BILL-77", Money.inr("2000.00"), status, null, "AUTO",
                "RAZORPAY", "order_1", "CAPTURED".equals(status) ? "pay_77" : null, false, null, null, at, null);
        send(Topics.PAYMENT_EVENTS, paymentId, new EventEnvelope<>(Ids.newId(), type, 1, at, "payment-service",
                "Payment", paymentId.toString(), version, merchantId, "corr", null, payload));
    }

    UUID refund(UUID paymentId, UUID refundId, String type, String status, String amount, long version, Instant at)
            throws Exception {
        var payload = new RefundEventPayload(refundId, paymentId, merchantId, "BILL-77", Money.inr(amount),
                Money.inr("COMPLETED".equals(status) ? amount : "0.00"), status, "partial", "rfnd_77", null, null, at);
        return send(Topics.REFUND_EVENTS, paymentId, new EventEnvelope<>(Ids.newId(), type, 1, at, "refund-service",
                "Refund", refundId.toString(), version, merchantId, "corr", null, payload));
    }

    UUID send(String topic, UUID key, EventEnvelope<?> envelope) throws Exception {
        kafka.send(topic, key.toString(), json.writeValueAsString(envelope)).get();
        return envelope.eventId();
    }

    String transactionRef(String sql, Object... args) {
        await().atMost(Duration.ofSeconds(60)).until(() -> !jdbc.queryForList(sql, String.class, args).isEmpty());
        return jdbc.queryForObject(sql, String.class, args);
    }

    @Test
    void buildsHistoryStatusLedgerAndReportsFromEvents() throws Exception {
        UUID paymentId = Ids.newId();
        UUID refundId = Ids.newId();

        // The refund completes "before" the capture is consumed: it must wait (blocking retry), not book against nothing.
        refund(paymentId, refundId, "RefundInitiated", "CREATED", "500.00", 0, t0.plusSeconds(120));
        refund(paymentId, refundId, "RefundCompleted", "COMPLETED", "500.00", 2, t0.plusSeconds(130));
        payment(paymentId, "PaymentInitiated", "CREATED", 0, t0);
        payment(paymentId, "PaymentCaptured", "CAPTURED", 3, t0.plusSeconds(60));
        // A stale, redelivered authorization must not move the status back.
        payment(paymentId, "PaymentAuthorized", "AUTHORIZED", 2, t0.plusSeconds(50));

        String paymentRef = transactionRef("SELECT transaction_ref FROM transactions WHERE payment_id = ? AND type = 'PAYMENT'",
                paymentId);
        await().atMost(Duration.ofSeconds(60)).until(() -> "COMPLETED".equals(jdbc.queryForList(
                "SELECT status FROM transactions WHERE refund_id = ?", String.class, refundId).stream().findFirst().orElse(null)));
        await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM transaction_events t JOIN transactions x ON x.id = t.transaction_id WHERE x.payment_id = ? AND x.type = 'PAYMENT'",
                Integer.class, paymentId) == 3);

        mvc.perform(get("/api/v1/transactions/{ref}", paymentRef).with(merchant(merchantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("PAYMENT"))
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.providerReference").value("pay_77"))
                .andExpect(jsonPath("$.timeline.length()").value(3))
                .andExpect(jsonPath("$.timeline[0].status").value("INITIATED"))
                .andExpect(jsonPath("$.ledgerEntries.length()").value(2))
                .andExpect(jsonPath("$.ledgerEntries[0].accountCode").value("PROVIDER_CLEARING"))
                .andExpect(jsonPath("$.ledgerEntries[0].direction").value("DEBIT"));

        mvc.perform(get("/api/v1/transactions").param("paymentId", paymentId.toString()).with(merchant(merchantId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));
        mvc.perform(get("/api/v1/transactions").param("type", "REFUND").with(merchant(merchantId)))
                .andExpect(jsonPath("$.content[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.content[0].amount.amount").value("500.00"));

        JsonNode summary = json.readTree(mvc.perform(get("/api/v1/reports/summary").with(merchant(merchantId))
                        .param("from", t0.minusSeconds(3600).toString()).param("to", Instant.now().plusSeconds(60).toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(summary.get(0).get("capturedCount").asLong()).isEqualTo(1);
        assertThat(summary.at("/0/capturedAmount/amount").asText()).isEqualTo("2000.00");
        assertThat(summary.at("/0/refundedAmount/amount").asText()).isEqualTo("500.00");
        assertThat(summary.at("/0/netAmount/amount").asText()).isEqualTo("1500.00");

        // Ledger: the hospital is owed 1500, clearing holds 1500, and everything balances.
        mvc.perform(get("/api/v1/admin/ledger/accounts/{code}/balance", "MERCHANT_PAYABLE:" + merchantId).with(ops()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].balance.amount").value("1500.00"));
        mvc.perform(get("/api/v1/admin/ledger/integrity").with(ops()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthy").value(true));

        // Each booking is published for reconciliation, keyed by payment, with the provider's reference.
        var created = outbox.findAll().stream().filter(e -> paymentId.toString().equals(e.getMessageKey())).toList();
        assertThat(created).extracting(com.hpg.messaging.outbox.OutboxEvent::getEventType)
                .containsExactly("TransactionCreated", "TransactionCreated");
        assertThat(created).extracting(e -> e.getTopic()).containsOnly("ledger.events.v1");
        assertThat(created).extracting(e -> e.getPayload().at("/payload/transactionType").asText() + ":"
                        + e.getPayload().at("/payload/providerReference").asText() + ":"
                        + e.getPayload().at("/payload/amount/amount").asText())
                .containsExactly("PAYMENT:pay_77:2000.00", "REFUND:rfnd_77:500.00");

        // The ledger is append-only, enforced by the database.
        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_entries SET amount = amount + 1"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entries")).hasMessageContaining("append-only");

        // Tenant isolation and roles.
        mvc.perform(get("/api/v1/transactions/{ref}", paymentRef).with(merchant(UUID.randomUUID())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/ledger/integrity").with(merchant(merchantId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void duplicateEventsAreBookedOnce() throws Exception {
        UUID paymentId = Ids.newId();
        var payload = new PaymentEventPayload(paymentId, merchantId, "BILL-DUP", Money.inr("300.00"), "CAPTURED", null,
                "AUTO", "RAZORPAY", "order_2", "pay_dup", false, null, null, t0, null);
        EventEnvelope<PaymentEventPayload> captured = new EventEnvelope<>(Ids.newId(), "PaymentCaptured", 1, t0,
                "payment-service", "Payment", paymentId.toString(), 2, merchantId, "corr", null, payload);
        send(Topics.PAYMENT_EVENTS, paymentId, captured);
        send(Topics.PAYMENT_EVENTS, paymentId, captured);
        payment(paymentId, "PaymentInitiated", "CREATED", 0, t0.minusSeconds(30)); // marker that both were consumed

        await().atMost(Duration.ofSeconds(60)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM transaction_events e JOIN transactions t ON t.id = e.transaction_id WHERE t.payment_id = ?",
                Integer.class, paymentId) == 2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ledger_entries e JOIN transactions t ON t.id = e.transaction_id
                WHERE t.payment_id = ?""", Integer.class, paymentId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM transactions WHERE payment_id = ?", String.class, paymentId))
                .isEqualTo("CAPTURED");
    }

    @Test
    void settlementConfirmedByReconciliationIsBookedOnce() throws Exception {
        UUID paymentId = Ids.newId();
        var payload = new PaymentEventPayload(paymentId, merchantId, "BILL-SETTLE", Money.inr("1000.00"), "CAPTURED",
                null, "AUTO", "RAZORPAY", "order_s", "pay_settle", false, null, null, t0, null);
        send(Topics.PAYMENT_EVENTS, paymentId, new EventEnvelope<>(Ids.newId(), "PaymentCaptured", 1, t0,
                "payment-service", "Payment", paymentId.toString(), 2, merchantId, "corr", null, payload));
        String ref = await().atMost(Duration.ofSeconds(60)).until(() -> jdbc.query(
                "SELECT transaction_ref FROM transactions WHERE payment_id = ?", (rs, i) -> rs.getString(1), paymentId)
                .stream().findFirst().orElse(null), java.util.Objects::nonNull);

        // Reconciliation matched it twice (two reports listing the same payment): booked once.
        for (int i = 0; i < 2; i++) {
            var settled = new com.hpg.events.reconciliation.SettlementConfirmedPayload(Ids.newId(), merchantId, ref,
                    "pay_settle", Money.inr("1000.00"), Money.inr("20.00"), Money.inr("3.60"), "setl_1", t0);
            send(Topics.RECONCILIATION_EVENTS, paymentId, new EventEnvelope<>(Ids.newId(), "SettlementConfirmed", 1, t0,
                    "reconciliation-service", "Settlement", ref, 1, merchantId, "corr", null, settled));
        }
        await().atMost(Duration.ofSeconds(60)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM processed_events WHERE consumer_group = 'transaction-settlements'", Integer.class) >= 2);
        var entries = jdbc.queryForList("""
                SELECT e.account_code, e.direction, e.amount FROM ledger_entries e JOIN transactions t ON t.id = e.transaction_id
                WHERE t.payment_id = ? AND e.posting_type = 'SETTLEMENT' ORDER BY e.id""", paymentId);
        assertThat(entries).extracting(m -> m.get("account_code") + " " + m.get("direction") + " " + m.get("amount"))
                .containsExactly("SETTLEMENT_BANK DEBIT 976.40", "PROVIDER_FEES DEBIT 23.60", "PROVIDER_CLEARING CREDIT 1000.00");
        mvc.perform(get("/api/v1/admin/ledger/integrity").with(ops()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthy").value(true));
    }

}
