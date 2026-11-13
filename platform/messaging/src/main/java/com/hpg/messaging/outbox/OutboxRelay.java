package com.hpg.messaging.outbox;

import com.hpg.events.EventHeaders;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes committed outbox rows to Kafka (docs/06-kafka-events.md §5).
 *
 * <p>Delivery is <b>at least once</b>: a crash between "broker acknowledged" and "row marked published"
 * re-sends the event on the next run. Consumers deduplicate by {@code eventId}; this system never claims
 * exactly-once across Kafka and PostgreSQL.
 *
 * <p><b>Ordering.</b> Rows are published strictly in {@code id} order and each send is awaited before the
 * next is sent. On the first failure the batch stops, so a later event for the same aggregate can never
 * overtake an earlier one that has not been published yet.
 *
 * <p><b>Why the database transaction stays open across the sends.</b> The batch is selected
 * {@code FOR UPDATE SKIP LOCKED} and marked published in the same transaction, so a row cannot be
 * published twice by concurrent relays or lost by a half-finished update. That means network I/O happens
 * inside a transaction — normally something to avoid. It is bounded here: one relay thread, one
 * connection, a short per-send timeout, and the batch stops at the first failure, so a broker outage
 * costs one timeout per run rather than {@code batchSize} of them. The production alternative that
 * removes the trade-off entirely is change-data-capture (Debezium) reading the WAL.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, byte[]> kafka;
    private final OutboxProperties properties;
    private final MeterRegistry meters;
    private final Clock clock;

    public OutboxRelay(OutboxEventRepository repository, KafkaTemplate<String, byte[]> kafka,
            OutboxProperties properties, MeterRegistry meters, Clock clock) {
        this.repository = repository;
        this.kafka = kafka;
        this.properties = properties;
        this.meters = meters;
        this.clock = clock;
    }

    /** @return number of events published in this run */
    @Transactional
    public int publishBatch() {
        List<OutboxEvent> batch = repository.lockBatchToPublish(PageRequest.of(0, properties.batchSize()));
        if (batch.isEmpty()) {
            return 0;
        }
        int published = 0;
        for (OutboxEvent event : batch) {
            try {
                send(event);
                event.markPublished(clock.instant());
                published++;
                meters.counter("outbox.published", "topic", event.getTopic(), "type", event.getEventType()).increment();
            } catch (RuntimeException e) {
                // Stop the batch: publishing later events now would reorder this aggregate's stream.
                event.recordFailure(e.toString());
                meters.counter("outbox.failures", "topic", event.getTopic()).increment();
                log.warn("Outbox publish failed for eventId={} topic={} (attempt {}); stopping this batch: {}",
                        event.getEventId(), event.getTopic(), event.getAttempts(), e.toString());
                break;
            }
        }
        if (published > 0) {
            log.debug("Published {} outbox event(s)", published);
        }
        return published;
    }

    private void send(OutboxEvent event) {
        // The stored JSON is sent byte for byte: what a consumer reads is exactly what was committed.
        byte[] value = event.getPayload().toString().getBytes(StandardCharsets.UTF_8);
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(event.getTopic(), event.getMessageKey(), value);
        record.headers().add(EventHeaders.EVENT_ID, event.getEventId().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.EVENT_TYPE, event.getEventType().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EventHeaders.AGGREGATE_TYPE, event.getAggregateType().getBytes(StandardCharsets.UTF_8));
        try {
            kafka.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing " + event.getEventId(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new OutboxPublishException(event.getEventId().toString(), e);
        }
    }

    /** Published rows are transport, not records: the business tables and the ledger are the records. */
    @Transactional
    public int deletePublishedOlderThan(Duration retention) {
        return repository.deletePublishedBefore(clock.instant().minus(retention));
    }

    public OutboxProperties properties() {
        return properties;
    }

    /** Thrown when the broker did not acknowledge an event within the send timeout. */
    public static class OutboxPublishException extends RuntimeException {
        public OutboxPublishException(String eventId, Throwable cause) {
            super("Kafka did not acknowledge event " + eventId, cause);
        }
    }
}
