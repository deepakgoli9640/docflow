package com.hpg.messaging.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * An event as a consumer sees it: the envelope fields plus the payload still as JSON.
 *
 * <p>The payload is converted to a concrete type only when the consumer asks for it
 * ({@link #payload(Class, ObjectMapper)}). Deserialising straight into a type named by a message header
 * — Spring Kafka's default type mapping — lets whoever can write to the topic choose which class is
 * instantiated, so the target type is decided here, in code, instead.
 */
public record ReceivedEvent(
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
        byte[] raw) {

    public <T> T payload(Class<T> type, ObjectMapper mapper) {
        return mapper.convertValue(payload, type);
    }

    /**
     * Payload and raw bytes are deliberately left out: payment events carry patient contact details, and
     * an accidental {@code log.info("... {}", event)} must not put them in the logs.
     */
    @Override
    public String toString() {
        return "ReceivedEvent[" + eventType + " v" + eventVersion + ", eventId=" + eventId
                + ", aggregateId=" + aggregateId + ", correlationId=" + correlationId + "]";
    }
}
