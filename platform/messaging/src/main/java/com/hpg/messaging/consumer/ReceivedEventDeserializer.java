package com.hpg.messaging.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.common.serialization.Deserializer;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

/**
 * Turns the raw event JSON into a {@link ReceivedEvent}.
 *
 * <p>A body that cannot be parsed, or that is missing the fields every event must have, throws. The
 * container's error handler treats that as a poison pill and dead-letters the record immediately:
 * retrying a malformed message would only block the partition forever.
 */
public class ReceivedEventDeserializer implements Deserializer<ReceivedEvent> {

    private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();

    @Override
    public ReceivedEvent deserialize(String topic, byte[] data) {
        if (data == null) {
            // A null value on a compacted topic is a tombstone (the key was deleted upstream).
            return null;
        }
        JsonNode root;
        try {
            root = mapper.readTree(data);
        } catch (IOException e) {
            throw new MalformedEventException("Event on " + topic + " is not valid JSON", e);
        }
        try {
            return new ReceivedEvent(
                    UUID.fromString(required(root, "eventId", topic)),
                    required(root, "eventType", topic),
                    root.path("eventVersion").asInt(1),
                    Instant.parse(required(root, "occurredAt", topic)),
                    root.path("producer").asText(null),
                    root.path("aggregateType").asText(null),
                    required(root, "aggregateId", topic),
                    root.path("aggregateVersion").asLong(0),
                    root.hasNonNull("merchantId") ? UUID.fromString(root.get("merchantId").asText()) : null,
                    root.path("correlationId").asText(null),
                    root.path("causationId").asText(null),
                    root.path("payload"),
                    data);
        } catch (MalformedEventException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MalformedEventException("Event on " + topic + " has an unusable envelope", e);
        }
    }

    private static String required(JsonNode root, String field, String topic) {
        if (!root.hasNonNull(field)) {
            throw new MalformedEventException("Event on " + topic + " has no '" + field + "'", null);
        }
        return root.get(field).asText();
    }

    /** Never retryable: the bytes will not improve by reading them again. */
    public static class MalformedEventException extends RuntimeException {
        public MalformedEventException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
