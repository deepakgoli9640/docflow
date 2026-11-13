package com.hpg.messaging.consumer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Consumer-side deduplication (docs/06-kafka-events.md §6). The marker is inserted in the SAME transaction
 * as the effect of the event, so "processed" and "effect applied" commit or roll back together. Every
 * consuming service creates the {@code processed_events} table in its own migrations.
 */
public class ProcessedEvents {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ProcessedEvents(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** @return true the first time this consumer group sees the event; false for a redelivery */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(String consumerGroup, UUID eventId) {
        return jdbc.update("""
                INSERT INTO processed_events (consumer_group, event_id, processed_at) VALUES (?, ?, ?)
                ON CONFLICT (consumer_group, event_id) DO NOTHING""",
                consumerGroup, eventId, Timestamp.from(clock.instant())) == 1;
    }

    /** Purges markers older than the topics' retention (+ margin): those events can no longer be redelivered. */
    public int purgeOlderThan(Instant cutoff) {
        return jdbc.update("DELETE FROM processed_events WHERE processed_at < ?", Timestamp.from(cutoff));
    }
}
