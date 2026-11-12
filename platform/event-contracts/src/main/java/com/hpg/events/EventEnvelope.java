package com.hpg.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Metadata wrapped around every event. See docs/06-kafka-events.md §3.
 *
 * @param eventId          unique id (UUIDv7); consumers deduplicate on it
 * @param eventType        e.g. {@code PaymentCaptured}
 * @param eventVersion     schema version of {@code payload}
 * @param occurredAt       when the business fact happened (UTC)
 * @param producer         service name
 * @param aggregateType    e.g. {@code Payment}
 * @param aggregateId      id of the aggregate; also the Kafka message key
 * @param aggregateVersion optimistic-lock version after the change; lets consumers drop stale events
 * @param merchantId       tenant the event belongs to
 * @param correlationId    business request id that started the flow
 * @param causationId      id of whatever directly caused this event (request, webhook, other event)
 * @param payload          event-specific body
 */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String producer,
        String aggregateType,
        String aggregateId,
        long aggregateVersion,
        UUID merchantId,
        String correlationId,
        String causationId,
        T payload) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(payload, "payload");
    }
}
