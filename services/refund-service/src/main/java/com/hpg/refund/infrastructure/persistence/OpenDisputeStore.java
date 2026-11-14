package com.hpg.refund.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Disputes still undecided, per payment (fed by dispute.events.v1). */
@Component
public class OpenDisputeStore {

    private final JdbcTemplate jdbc;

    public OpenDisputeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void opened(UUID disputeId, UUID paymentId, UUID merchantId, Instant at) {
        jdbc.update("INSERT INTO open_disputes (dispute_id, payment_id, merchant_id, opened_at) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT (dispute_id) DO NOTHING", disputeId, paymentId, merchantId, Timestamp.from(at));
    }

    public void resolved(UUID disputeId) {
        jdbc.update("DELETE FROM open_disputes WHERE dispute_id = ?", disputeId);
    }

    public boolean isDisputed(UUID paymentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM open_disputes WHERE payment_id = ?)", Boolean.class, paymentId));
    }
}
