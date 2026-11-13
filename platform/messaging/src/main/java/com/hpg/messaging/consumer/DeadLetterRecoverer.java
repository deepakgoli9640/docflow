package com.hpg.messaging.consumer;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.support.serializer.DeserializationException;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Last stop for a record this service cannot process: republish it to {@code <topic>.dlt} with enough
 * context to investigate and replay it later. Replay is safe because consumers deduplicate by event id.
 *
 * <p>The <em>original bytes</em> are forwarded, not a re-serialised object, so a dead-lettered record can
 * be replayed exactly as it arrived — including a record that failed because it could not be parsed.
 *
 * <p>Writing it ourselves rather than using {@code DeadLetterPublishingRecoverer} keeps one value type
 * (bytes) on the dead-letter path and makes the headers explicit.
 */
public class DeadLetterRecoverer implements ConsumerRecordRecoverer {

    public static final String DLT_SUFFIX = ".dlt";
    public static final String HEADER_ORIGINAL_TOPIC = "hpg-dlt-original-topic";
    public static final String HEADER_ORIGINAL_PARTITION = "hpg-dlt-original-partition";
    public static final String HEADER_ORIGINAL_OFFSET = "hpg-dlt-original-offset";
    public static final String HEADER_EXCEPTION_TYPE = "hpg-dlt-exception-type";
    public static final String HEADER_EXCEPTION_MESSAGE = "hpg-dlt-exception-message";
    public static final String HEADER_SERVICE = "hpg-dlt-service";

    private static final Logger log = LoggerFactory.getLogger(DeadLetterRecoverer.class);

    private final KafkaTemplate<String, byte[]> kafka;
    private final MeterRegistry meters;
    private final String serviceName;

    public DeadLetterRecoverer(KafkaTemplate<String, byte[]> kafka, MeterRegistry meters, String serviceName) {
        this.kafka = kafka;
        this.meters = meters;
        this.serviceName = serviceName;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        Throwable cause = rootCause(exception);
        String target = record.topic() + DLT_SUFFIX;
        log.error("Dead-lettering record from {}-{} offset {} to {}: {}", record.topic(), record.partition(),
                record.offset(), target, cause.toString());

        ProducerRecord<String, byte[]> dlt = new ProducerRecord<>(target,
                record.key() == null ? null : record.key().toString(), originalBytes(record, exception));
        record.headers().forEach(dlt.headers()::add);
        header(dlt, HEADER_ORIGINAL_TOPIC, record.topic());
        header(dlt, HEADER_ORIGINAL_PARTITION, String.valueOf(record.partition()));
        header(dlt, HEADER_ORIGINAL_OFFSET, String.valueOf(record.offset()));
        header(dlt, HEADER_SERVICE, serviceName);
        header(dlt, HEADER_EXCEPTION_TYPE, cause.getClass().getName());
        header(dlt, HEADER_EXCEPTION_MESSAGE, String.valueOf(cause.getMessage()));
        try {
            kafka.send(dlt).get(10, TimeUnit.SECONDS);
            meters.counter("kafka.dlt.records", "topic", record.topic(), "service", serviceName,
                    "exception", cause.getClass().getSimpleName()).increment();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while dead-lettering", e);
        } catch (Exception e) {
            // Rethrow: the offset must not be committed, or the record would be lost entirely.
            throw new IllegalStateException("Could not publish to " + target, e);
        }
    }

    private static byte[] originalBytes(ConsumerRecord<?, ?> record, Exception exception) {
        for (Throwable t = exception; t != null; t = t.getCause()) {
            if (t instanceof DeserializationException de) {
                return de.getData();
            }
        }
        Object value = record.value();
        if (value instanceof ReceivedEvent event) {
            return event.raw();
        }
        if (value instanceof byte[] bytes) {
            return bytes;
        }
        return value == null ? null : String.valueOf(value).getBytes(StandardCharsets.UTF_8);
    }

    private static void header(ProducerRecord<String, byte[]> record, String name, String value) {
        record.headers().remove(name);
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
