package com.hpg.messaging.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Publishes outbox rows to Kafka (docs/06-kafka-events.md §5).
 *
 * <ul>
 *   <li><b>One active relay per service.</b> Each batch first takes a transaction-scoped Postgres advisory
 *       lock; replicas that do not get it skip the round. Two relays working side by side could publish
 *       later events of a payment before earlier ones.</li>
 *   <li><b>Order.</b> Rows are read by {@code id}. If a send fails the batch <em>stops</em>, so nothing
 *       later overtakes the failed row.</li>
 *   <li><b>At least once.</b> A row is marked published only after Kafka acknowledged it ({@code acks=all}).
 *       A crash in between republishes it; consumers deduplicate by {@code eventId}.</li>
 * </ul>
 * Runs on its own single thread, so services need no {@code @EnableScheduling} for it.
 */
public class OutboxRelay implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    /** Arbitrary constant: the advisory lock id for "outbox relay" within each service's database. */
    static final long RELAY_LOCK_ID = 0x4850475F4F555458L; // "HPG_OUTX"

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final KafkaTemplate<String, String> kafka;
    private final OutboxRelayProperties properties;
    private final Clock clock;
    private ScheduledExecutorService executor;
    private volatile boolean running;
    private Instant lastCleanup = Instant.EPOCH;

    public OutboxRelay(JdbcTemplate jdbc, TransactionTemplate tx, KafkaTemplate<String, String> kafka,
            OutboxRelayProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.kafka = kafka;
        this.properties = properties;
        this.clock = clock;
    }

    private record Row(long id, String eventId, String eventType, String topic, String key, String payload,
            String traceparent) {
    }

    /**
     * Publishes up to one batch.
     *
     * @return number of rows published (0 if another replica holds the relay lock or nothing is pending)
     */
    public int publishBatch() {
        Integer published = tx.execute(status -> {
            Boolean locked = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, RELAY_LOCK_ID);
            if (!Boolean.TRUE.equals(locked)) {
                return 0;
            }
            List<Row> rows = jdbc.query("""
                            SELECT id, event_id, event_type, topic, message_key, payload::text, payload->>'traceparent'
                            FROM outbox_events WHERE published_at IS NULL ORDER BY id LIMIT ?""",
                    (rs, i) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getString(7)),
                    properties.batchSize());
            List<Object[]> done = new ArrayList<>();
            Timestamp now = Timestamp.from(clock.instant());
            for (Row row : rows) {
                try {
                    send(row);
                    done.add(new Object[] {now, row.id()});
                } catch (Exception e) {
                    String error = rootMessage(e);
                    log.warn("Outbox relay could not publish id={} eventType={} topic={}: {}. Stopping this batch "
                            + "to preserve order.", row.id(), row.eventType(), row.topic(), error);
                    jdbc.update("UPDATE outbox_events SET attempts = attempts + 1, last_error = ? WHERE id = ?",
                            truncate(error, 1000), row.id());
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    break;
                }
            }
            if (!done.isEmpty()) {
                jdbc.batchUpdate("UPDATE outbox_events SET published_at = ?, attempts = attempts + 1 WHERE id = ?", done);
            }
            return done.size();
        });
        return published == null ? 0 : published;
    }

    /** Deletes published rows older than the retention. Outbox rows are transport, not records. */
    public int cleanup() {
        Instant cutoff = clock.instant().minus(properties.retention());
        return jdbc.update("DELETE FROM outbox_events WHERE published_at IS NOT NULL AND published_at < ?",
                Timestamp.from(cutoff));
    }

    private void send(Row row) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(row.topic(), row.key(), row.payload());
        record.headers().add(new RecordHeader("eventId", row.eventId().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("eventType", row.eventType().getBytes(StandardCharsets.UTF_8)));
        if (row.traceparent() != null) {
            // Continues the trace of the request that wrote the event (W3C trace context); consumers' listener
            // observations pick it up, so one trace spans request -> outbox -> Kafka -> consumers.
            record.headers().add(new RecordHeader("traceparent", row.traceparent().getBytes(StandardCharsets.UTF_8)));
        }
        kafka.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void tick() {
        try {
            int published;
            do {
                published = publishBatch();
            } while (running && published == properties.batchSize());
            Instant now = clock.instant();
            if (Duration.between(lastCleanup, now).compareTo(Duration.ofHours(1)) > 0) {
                lastCleanup = now;
                int deleted = cleanup();
                if (deleted > 0) {
                    log.info("Outbox cleanup deleted {} published rows", deleted);
                }
            }
        } catch (RuntimeException e) {
            // Database unavailable etc. Never let the exception kill the scheduled task.
            log.warn("Outbox relay round failed: {}", rootMessage(e));
        }
    }

    @Override
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "outbox-relay");
            t.setDaemon(true);
            return t;
        });
        running = true;
        long interval = properties.interval().toMillis();
        executor.scheduleWithFixedDelay(this::tick, interval, interval, TimeUnit.MILLISECONDS);
        log.info("Outbox relay started (interval={}, batchSize={})", properties.interval(), properties.batchSize());
    }

    @Override
    public void stop() {
        running = false;
        if (executor != null) {
            executor.shutdown();
            try {
                executor.awaitTermination(properties.sendTimeout().toMillis() + 1000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
