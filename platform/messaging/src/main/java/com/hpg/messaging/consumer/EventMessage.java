package com.hpg.messaging.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * An event envelope as received from Kafka, with the payload still as JSON. Consumers look at
 * {@link #eventType()} first and only then bind the payload to the record type they expect, so unknown
 * event types on a topic can be skipped without failing.
 */
public record EventMessage(
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
        JsonNode payload,
        ObjectMapper mapper) {

    public <T> T payloadAs(Class<T> type) {
        try {
            return mapper.treeToValue(payload, type);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new NonRetryableEventException("Payload of " + eventType + " " + eventId + " is not a valid "
                    + type.getSimpleName(), e);
        }
    }

    @Override
    public String toString() {
        return "EventMessage[eventId=" + eventId + ", eventType=" + eventType + ", aggregateId=" + aggregateId
                + ", aggregateVersion=" + aggregateVersion + "]";
    }
}
