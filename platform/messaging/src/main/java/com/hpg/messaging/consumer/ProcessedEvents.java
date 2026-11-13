package com.hpg.messaging.consumer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Consumer-side deduplication (docs/06-kafka-events.md §6).
 *
 * <p>Kafka delivery is at least once, so the same event can arrive twice: the relay re-sent it, a
 * rebalance replayed uncommitted offsets, or an operator replayed a dead-letter record. Claiming the
 * event in the <em>same transaction</em> as its effect makes processing effectively once inside this
 * service: either both the claim and the effect commit, or neither does.
 *
 * <p>{@code ON CONFLICT DO NOTHING} rather than "select then insert", because two consumer threads could
 * otherwise both pass the check.
 */
public class ProcessedEvents {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ProcessedEvents(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** @return true if this consumer group has not processed the event before (so it should be processed) */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claim(String consumerGroup, UUID eventId) {
        return jdbc.update("""
                INSERT INTO processed_events (consumer_group, event_id, processed_at)
                VALUES (?, ?, ?)
                ON CONFLICT (consumer_group, event_id) DO NOTHING""",
                consumerGroup, eventId, Timestamp.from(clock.instant())) == 1;
    }

    /** Rows outlive topic retention by a margin, then go: they only exist to catch redeliveries. */
    public int deleteOlderThan(Instant before, int limit) {
        return jdbc.update("""
                DELETE FROM processed_events WHERE ctid IN (
                    SELECT ctid FROM processed_events WHERE processed_at < ? LIMIT ?)""",
                Timestamp.from(before), limit);
    }
}
