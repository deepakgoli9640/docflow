package com.hpg.messaging.deadletter;

import com.hpg.events.Topics;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Dead letters: events a consumer could not process after all its retries ({@code <topic>.dlt}).
 *
 * <p><b>Inspect</b>: each record keeps the original topic, partition, offset, the consumer group that failed and
 * the exception, as Spring Kafka's dead-letter recoverer wrote them.
 *
 * <p><b>Replay</b> after the cause is fixed: the record is published again, unchanged (same key, eventId,
 * eventType, trace), to its original topic. Every consumer group sees it again, but all consumers deduplicate by
 * eventId ({@code processed_events}), so only the group that failed it acts. Each replay is recorded; a
 * second replay of the same record is refused unless forced.
 */
public class DeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);
    private static final List<String> SOURCE_TOPICS = List.of(Topics.PAYMENT_EVENTS, Topics.REFUND_EVENTS,
            Topics.LEDGER_EVENTS, Topics.MERCHANT_SNAPSHOTS, Topics.RECONCILIATION_EVENTS, Topics.NOTIFICATION_EVENTS,
            Topics.DISPUTE_EVENTS);

    public record DeadLetter(String dltTopic, int partition, long offset, String key, String originalTopic,
            String consumerGroup, String eventId, String eventType, String exception, String exceptionMessage,
            Instant deadLetteredAt, Instant replayedAt, String replayedBy) {
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    public static class AlreadyReplayedException extends RuntimeException {
        public AlreadyReplayedException(String message) {
            super(message);
        }
    }

    private final Supplier<Map<String, Object>> consumerConfig;
    private final KafkaTemplate<String, String> kafka;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public DeadLetterService(Supplier<Map<String, Object>> consumerConfig, KafkaTemplate<String, String> kafka,
            JdbcTemplate jdbc, Clock clock) {
        this.consumerConfig = consumerConfig;
        this.kafka = kafka;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** The newest dead letters (all DLT topics, or one source topic), newest first. */
    public List<DeadLetter> list(String sourceTopic, int limit) {
        List<String> dlts = (sourceTopic == null || sourceTopic.isBlank() ? SOURCE_TOPICS : List.of(sourceTopic))
                .stream().map(Topics::dlt).toList();
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = consumer()) {
            List<TopicPartition> partitions = new ArrayList<>();
            Map<String, List<PartitionInfo>> known = consumer.listTopics(Duration.ofSeconds(5));
            for (String dlt : dlts) {
                for (PartitionInfo p : known.getOrDefault(dlt, List.of())) {
                    partitions.add(new TopicPartition(dlt, p.partition()));
                }
            }
            if (partitions.isEmpty()) {
                return List.of();
            }
            consumer.assign(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            Map<TopicPartition, Long> begin = consumer.beginningOffsets(partitions);
            for (TopicPartition tp : partitions) {
                // Only the last `limit` records of each partition: the newest are what an operator looks at.
                consumer.seek(tp, Math.max(begin.get(tp), end.get(tp) - limit));
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!reachedEnd(consumer, partitions, end) && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
        }
        Map<String, Map<String, Object>> replays = replays();
        return records.stream()
                .sorted(Comparator.comparingLong(ConsumerRecord<String, String>::timestamp).reversed())
                .limit(limit)
                .map(r -> toDeadLetter(r, replays.get(id(r.topic(), r.partition(), r.offset()))))
                .toList();
    }

    /** Publishes one dead letter again to its original topic. */
    public DeadLetter replay(String dltTopic, int partition, long offset, String operator, boolean force) {
        if (!dltTopic.endsWith(Topics.DLT_SUFFIX)) {
            throw new NotFoundException(dltTopic + " is not a dead-letter topic");
        }
        ConsumerRecord<String, String> record = fetch(dltTopic, partition, offset)
                .orElseThrow(() -> new NotFoundException("No record at " + id(dltTopic, partition, offset)));
        String original = header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC)
                .orElse(dltTopic.substring(0, dltTopic.length() - Topics.DLT_SUFFIX.length()));
        Instant now = clock.instant();
        try {
            jdbc.update("""
                    INSERT INTO dead_letter_replays (dlt_topic, dlt_partition, dlt_offset, original_topic, event_id,
                                                     replayed_by, replayed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""", dltTopic, partition, offset, original,
                    header(record, "eventId").orElse(null), operator, Timestamp.from(now));
        } catch (DuplicateKeyException e) {
            if (!force) {
                throw new AlreadyReplayedException("Already replayed: " + id(dltTopic, partition, offset)
                        + ". Replay again with force=true if the first replay failed as well.");
            }
            jdbc.update("UPDATE dead_letter_replays SET replayed_by = ?, replayed_at = ? WHERE dlt_topic = ? "
                    + "AND dlt_partition = ? AND dlt_offset = ?", operator, Timestamp.from(now), dltTopic, partition, offset);
        }
        ProducerRecord<String, String> again = new ProducerRecord<>(original, record.key(), record.value());
        for (String name : List.of("eventId", "eventType", "traceparent")) {
            header(record, name).ifPresent(v -> again.headers().add(name, v.getBytes(StandardCharsets.UTF_8)));
        }
        again.headers().add("hpg-replayed-from", id(dltTopic, partition, offset).getBytes(StandardCharsets.UTF_8));
        try {
            kafka.send(again).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Not published: forget the replay so it can be tried again without force.
            jdbc.update("DELETE FROM dead_letter_replays WHERE dlt_topic = ? AND dlt_partition = ? AND dlt_offset = ?",
                    dltTopic, partition, offset);
            throw new IllegalStateException("Could not publish the replay: " + e.getMessage(), e);
        }
        log.warn("Dead letter {} replayed to {} by {} (eventId={})", id(dltTopic, partition, offset), original, operator,
                header(record, "eventId").orElse("?"));
        return toDeadLetter(record, Map.of("replayed_at", Timestamp.from(now), "replayed_by", operator));
    }

    private Optional<ConsumerRecord<String, String>> fetch(String topic, int partition, long offset) {
        try (KafkaConsumer<String, String> consumer = consumer()) {
            TopicPartition tp = new TopicPartition(topic, partition);
            consumer.assign(List.of(tp));
            consumer.seek(tp, offset);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(200))) {
                    if (r.offset() == offset) {
                        return Optional.of(r);
                    }
                    if (r.offset() > offset) {
                        return Optional.empty();
                    }
                }
                if (consumer.position(tp) > offset) {
                    return Optional.empty();
                }
            }
            return Optional.empty();
        } catch (org.apache.kafka.common.KafkaException e) {
            return Optional.empty();
        }
    }

    private KafkaConsumer<String, String> consumer() {
        Map<String, Object> config = new HashMap<>(consumerConfig.get());
        config.remove(ConsumerConfig.GROUP_ID_CONFIG);          // assign(), never commits: reading is side-effect free
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        return new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer());
    }

    private static boolean reachedEnd(KafkaConsumer<?, ?> consumer, List<TopicPartition> partitions,
            Map<TopicPartition, Long> end) {
        return partitions.stream().allMatch(tp -> consumer.position(tp) >= end.get(tp));
    }

    private Map<String, Map<String, Object>> replays() {
        return jdbc.queryForList("SELECT dlt_topic, dlt_partition, dlt_offset, replayed_by, replayed_at FROM dead_letter_replays")
                .stream().collect(Collectors.toMap(
                        m -> id((String) m.get("dlt_topic"), ((Number) m.get("dlt_partition")).intValue(),
                                ((Number) m.get("dlt_offset")).longValue()), m -> m, (a, b) -> a));
    }

    private static DeadLetter toDeadLetter(ConsumerRecord<String, String> r, Map<String, Object> replay) {
        Instant replayedAt = replay == null ? null : ((Timestamp) replay.get("replayed_at")).toInstant();
        return new DeadLetter(r.topic(), r.partition(), r.offset(), r.key(),
                header(r, KafkaHeaders.DLT_ORIGINAL_TOPIC).orElse(null),
                header(r, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP).orElse(null),
                header(r, "eventId").orElse(null), header(r, "eventType").orElse(null),
                header(r, KafkaHeaders.DLT_EXCEPTION_FQCN).map(c -> c.substring(c.lastIndexOf('.') + 1)).orElse(null),
                header(r, KafkaHeaders.DLT_EXCEPTION_MESSAGE).map(m -> m.length() > 500 ? m.substring(0, 500) : m).orElse(null),
                Instant.ofEpochMilli(r.timestamp()), replayedAt, replay == null ? null : (String) replay.get("replayed_by"));
    }

    private static Optional<String> header(ConsumerRecord<?, ?> r, String name) {
        Header h = r.headers().lastHeader(name);
        return h == null || h.value() == null ? Optional.empty() : Optional.of(new String(h.value(), StandardCharsets.UTF_8));
    }

    private static String id(String topic, int partition, long offset) {
        return topic + "/" + partition + "/" + offset;
    }

    /** Topics whose dead letters this service can show. */
    public static Set<String> sourceTopics() {
        return Set.copyOf(SOURCE_TOPICS);
    }
}
