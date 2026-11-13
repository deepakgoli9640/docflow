package com.hpg.messaging.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One event waiting to be published. Written in the same transaction as the business change, so
 * "changed but never published" and "published but rolled back" are both impossible.
 *
 * <p>{@code id} is a database sequence: the relay publishes in {@code id} order, which preserves the
 * order of events for the same aggregate. Every service that uses the outbox creates the
 * {@code outbox_events} table in its own Flyway migrations (see payment-service V1).
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "topic", nullable = false, length = 100)
    private String topic;

    @Column(name = "message_key", nullable = false, length = 100)
    private String messageKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private JsonNode payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    protected OutboxEvent() {
        // for JPA
    }

    OutboxEvent(UUID eventId, String aggregateType, String aggregateId, String eventType, String topic,
            String messageKey, JsonNode payload, Instant createdAt) {
        this.eventId = eventId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.topic = topic;
        this.messageKey = messageKey;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    /** Marks the event as sent to Kafka. Called by the relay after the broker acknowledged it. */
    void markPublished(Instant when) {
        this.publishedAt = when;
        this.lastError = null;
    }

    /** Records a failed publish attempt; the row stays unpublished and is retried on the next run. */
    void recordFailure(String error) {
        this.attempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(1000, error.length()));
    }

    public Long getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTopic() {
        return topic;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public JsonNode getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }
}
