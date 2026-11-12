package com.hpg.messaging.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.common.correlation.CorrelationId;
import com.hpg.common.id.Ids;
import com.hpg.events.EventEnvelope;
import org.slf4j.MDC;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Appends events to the outbox. {@code MANDATORY} propagation: calling this outside the business
 * transaction is a programming error, because then the event and the change could diverge.
 */
public class OutboxWriter {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String producer;

    public OutboxWriter(OutboxEventRepository repository, ObjectMapper objectMapper, Clock clock, String producer) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.producer = producer;
    }

    /**
     * @param topic            Kafka topic
     * @param aggregateType    e.g. "Payment"
     * @param aggregateId      aggregate id; also the message key, so one aggregate's events stay ordered
     * @param aggregateVersion version after the change
     * @param merchantId       tenant
     * @param eventType        event type name
     * @param eventVersion     payload schema version
     * @param causationId      what directly caused this event (request id, webhook event id, ...)
     * @param payload          event body
     * @return the envelope that was stored
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public <T> EventEnvelope<T> append(String topic, String aggregateType, String aggregateId, long aggregateVersion,
            UUID merchantId, String eventType, int eventVersion, String causationId, T payload) {
        EventEnvelope<T> envelope = new EventEnvelope<>(Ids.newId(), eventType, eventVersion, clock.instant(), producer,
                aggregateType, aggregateId, aggregateVersion, merchantId, MDC.get(CorrelationId.MDC_KEY), causationId,
                payload);
        JsonNode json = objectMapper.valueToTree(envelope);
        repository.save(new OutboxEvent(envelope.eventId(), aggregateType, aggregateId, eventType, topic, aggregateId,
                json, envelope.occurredAt()));
        return envelope;
    }
}
