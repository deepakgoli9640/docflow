package com.hpg.transaction.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code transaction_events}: every event that touched a transaction, in the order it happened. */
@Component
public class TransactionTimeline {

    private final JdbcTemplate jdbc;

    public TransactionTimeline(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Entry(UUID eventId, String eventType, String status, long sourceVersion, Instant occurredAt) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID transactionId, UUID eventId, String eventType, String status, long sourceVersion,
            Instant occurredAt, Instant now) {
        jdbc.update("""
                        INSERT INTO transaction_events (transaction_id, event_id, event_type, status, source_version,
                                                        occurred_at, recorded_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (event_id) DO NOTHING""",
                transactionId, eventId, eventType, status, sourceVersion, Timestamp.from(occurredAt),
                Timestamp.from(now));
    }

    public List<Entry> of(UUID transactionId) {
        return jdbc.query("""
                        SELECT event_id, event_type, status, source_version, occurred_at FROM transaction_events
                        WHERE transaction_id = ? ORDER BY occurred_at, source_version, id""",
                (rs, i) -> new Entry(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getLong(4),
                        rs.getTimestamp(5).toInstant()),
                transactionId);
    }

    public long nextReferenceNumber() {
        Long n = jdbc.queryForObject("SELECT nextval('transaction_ref_seq')", Long.class);
        return n == null ? 0 : n;
    }
}
