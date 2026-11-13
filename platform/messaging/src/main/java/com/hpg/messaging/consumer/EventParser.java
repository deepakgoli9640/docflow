package com.hpg.messaging.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

/** Turns a Kafka record value into an {@link EventMessage}. Malformed records are poison pills: not retried. */
public class EventParser {

    private final ObjectMapper mapper;

    public EventParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public EventMessage parse(String value) {
        if (value == null || value.isBlank()) {
            throw new NonRetryableEventException("Empty event record", null);
        }
        try {
            JsonNode n = mapper.readTree(value);
            String eventType = text(n, "eventType");
            String eventId = text(n, "eventId");
            if (eventType == null || eventId == null || !n.hasNonNull("payload")) {
                throw new NonRetryableEventException("Record is not an HPG event envelope", null);
            }
            String occurredAt = text(n, "occurredAt");
            String merchantId = text(n, "merchantId");
            return new EventMessage(UUID.fromString(eventId), eventType, n.path("eventVersion").asInt(1),
                    occurredAt == null ? null : Instant.parse(occurredAt), text(n, "producer"),
                    text(n, "aggregateType"), text(n, "aggregateId"), n.path("aggregateVersion").asLong(0),
                    merchantId == null ? null : UUID.fromString(merchantId), text(n, "correlationId"),
                    text(n, "causationId"), n.get("payload"), mapper);
        } catch (IOException | IllegalArgumentException | java.time.format.DateTimeParseException e) {
            throw new NonRetryableEventException("Malformed event record", e);
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
