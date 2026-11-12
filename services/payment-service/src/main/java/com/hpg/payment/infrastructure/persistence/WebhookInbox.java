package com.hpg.payment.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Inbox of provider webhooks, deduplicated by the provider's event id. The insert and the processing
 * happen in one transaction: if processing fails, the inbox row rolls back too, we answer 5xx and the
 * provider redelivers. A redelivery of an event we already processed hits the unique key and is
 * acknowledged without processing it again.
 */
@Component
public class WebhookInbox {

    private final JdbcTemplate jdbc;

    public WebhookInbox(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return the inbox row id, or null if this event was already received */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long receive(String provider, String eventId, String eventType, String payloadJson, Instant now) {
        return jdbc.query("""
                        INSERT INTO webhook_inbox (provider, provider_event_id, event_type, payload, received_at)
                        VALUES (?, ?, ?, ?::jsonb, ?)
                        ON CONFLICT (provider, provider_event_id) DO NOTHING
                        RETURNING id""",
                rs -> rs.next() ? rs.getLong(1) : null,
                provider, eventId, eventType, payloadJson, Timestamp.from(now));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(long id, String outcome, String detail, Instant now) {
        jdbc.update("UPDATE webhook_inbox SET processed_at = ?, outcome = ?, detail = ? WHERE id = ?",
                Timestamp.from(now), outcome, detail == null ? null : detail.substring(0, Math.min(500, detail.length())), id);
    }
}
