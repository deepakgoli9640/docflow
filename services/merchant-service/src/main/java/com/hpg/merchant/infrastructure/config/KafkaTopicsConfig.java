package com.hpg.merchant.infrastructure.config;

import com.hpg.events.Topics;
import com.hpg.messaging.config.HpgTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topics merchant-service owns. {@code merchant.snapshots.v1} is <b>compacted</b>: Kafka keeps the newest
 * record per merchant for ever, so a new or rebuilt consumer gets every merchant's current configuration
 * by reading the topic from the beginning — no backfill endpoint, no synchronous call.
 */
@Configuration
@ConditionalOnProperty(name = "spring.kafka.admin.auto-create", havingValue = "true", matchIfMissing = true)
public class KafkaTopicsConfig {

    @Bean
    public NewTopic merchantSnapshotsTopic(@Value("${hpg.kafka.replication-factor:1}") short replicationFactor,
            @Value("${hpg.kafka.merchant-snapshot-partitions:1}") int partitions) {
        return HpgTopics.compactedSnapshots(Topics.MERCHANT_SNAPSHOTS, partitions, replicationFactor);
    }
}
