package com.hpg.messaging.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.kafka.config.TopicBuilder;

import java.util.Map;

/**
 * Topic definitions (docs/06-kafka-events.md §2). Each service declares the topics it produces and the
 * dead-letter topics it needs, as {@code NewTopic} beans; Spring's {@code KafkaAdmin} then creates them
 * at startup if they are missing. That is a local-development convenience — in production topics are
 * created deliberately, with the replication factor and retention the environment needs.
 *
 * <p>Declaring them matters even where Kafka would auto-create: an auto-created topic gets one partition
 * and time-based retention, which would silently break both partitioning and the compacted snapshot topic.
 */
public final class HpgTopics {

    private HpgTopics() {
    }

    /** An event stream: one partition per key range, keyed by aggregate id so a key's order is preserved. */
    public static NewTopic events(String name, int partitions, short replicationFactor) {
        return TopicBuilder.name(name).partitions(partitions).replicas(replicationFactor).build();
    }

    /**
     * A compacted "current state" topic: Kafka keeps the latest record per key for ever, so a new consumer
     * can rebuild its replica by reading from the beginning instead of asking the owning service.
     */
    public static NewTopic compactedSnapshots(String name, int partitions, short replicationFactor) {
        return TopicBuilder.name(name).partitions(partitions).replicas(replicationFactor)
                .configs(Map.of(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT,
                        TopicConfig.MIN_CLEANABLE_DIRTY_RATIO_CONFIG, "0.1"))
                .build();
    }

    /** Dead letters are kept longer than live events: someone has to look at them and replay them. */
    public static NewTopic deadLetter(String sourceTopic, short replicationFactor) {
        return TopicBuilder.name(sourceTopic + ".dlt").partitions(1).replicas(replicationFactor)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(java.time.Duration.ofDays(30).toMillis()))
                .build();
    }
}
