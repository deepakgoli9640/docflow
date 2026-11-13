package com.hpg.messaging.consumer;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TopicConfigTest {

    @Test
    void localDefaultsAreOneReplicaWithBrokerDefaultIsr() {
        NewTopic t = KafkaMessagingAutoConfiguration.topic("payment.events.v1", 3, props(1, 0), false);
        assertThat(t.numPartitions()).isEqualTo(3);
        assertThat(t.replicationFactor()).isEqualTo((short) 1);
        assertThat(t.configs() == null ? java.util.Map.of() : t.configs()).doesNotContainKey("min.insync.replicas");
    }

    @Test
    void productionTopicsAreReplicatedWithMinIsr() {
        NewTopic t = KafkaMessagingAutoConfiguration.topic("merchant.snapshots.v1", 6, props(3, 2), true);
        assertThat(t.replicationFactor()).isEqualTo((short) 3);
        assertThat(t.configs()).containsEntry("min.insync.replicas", "2").containsEntry("cleanup.policy", "compact");
    }

    private static KafkaConsumerProperties props(int replicas, int minIsr) {
        return new KafkaConsumerProperties(12, Duration.ofSeconds(1), Duration.ofSeconds(30), 3, replicas, minIsr);
    }
}
