package com.hpg.messaging.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.common.correlation.CorrelationId;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;

/**
 * Copies the envelope's {@code correlationId} into the log context (MDC) while a record is processed, so a
 * consumer's log lines carry the same business handle as the request that started the flow, hours later.
 */
public class CorrelationIdRecordInterceptor implements RecordInterceptor<Object, Object> {

    private final ObjectMapper mapper;

    public CorrelationIdRecordInterceptor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        if (record.value() instanceof String value) {
            try {
                JsonNode id = mapper.readTree(value).get("correlationId");
                if (id != null && id.isTextual()) {
                    MDC.put(CorrelationId.MDC_KEY, id.asText());
                }
            } catch (java.io.IOException | RuntimeException notJson) {
                // a malformed record is handled (dead-lettered) by the listener; nothing to add to the log context
            }
        }
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        MDC.remove(CorrelationId.MDC_KEY);
    }
}
