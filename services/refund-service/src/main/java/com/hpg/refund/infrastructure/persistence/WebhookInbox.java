package com.hpg.refund.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;

/** Accepted (signature-valid) provider webhooks, deduplicated by the provider's event id. */
@Component
public class WebhookInbox {

    private final JdbcTemplate jdbc;

    public WebhookInbox(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if this event id is new */
    public boolean record(String provider, String eventId, String eventType, byte[] body, Instant now) {
        return jdbc.update("""
                        INSERT INTO webhook_inbox (provider, provider_event_id, event_type, payload, received_at)
                        VALUES (?, ?, ?, ?::jsonb, ?) ON CONFLICT (provider, provider_event_id) DO NOTHING""",
                provider, eventId, eventType, new String(body, StandardCharsets.UTF_8), Timestamp.from(now)) == 1;
    }

    public void outcome(String provider, String eventId, String outcome) {
        jdbc.update("UPDATE webhook_inbox SET outcome = ? WHERE provider = ? AND provider_event_id = ?", outcome,
                provider, eventId);
    }
}
