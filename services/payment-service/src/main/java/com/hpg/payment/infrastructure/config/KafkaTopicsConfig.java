package com.hpg.payment.infrastructure.config;

import com.hpg.events.Topics;
import com.hpg.messaging.config.HpgTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topics payment-service owns, plus the dead-letter topic for the stream it consumes.
 *
 * <p>{@code payment.events.v1} is keyed by payment id, so every event for one payment lands on the same
 * partition and is delivered in the order it happened. Partitions are the unit of parallelism: more
 * partitions allow more consumers, but a payment's own order is preserved regardless.
 */
@Configuration
@ConditionalOnProperty(name = "spring.kafka.admin.auto-create", havingValue = "true", matchIfMissing = true)
public class KafkaTopicsConfig {

    @Bean
    public NewTopic paymentEventsTopic(@Value("${hpg.kafka.replication-factor:1}") short replicationFactor,
            @Value("${hpg.kafka.payment-event-partitions:3}") int partitions) {
        return HpgTopics.events(Topics.PAYMENT_EVENTS, partitions, replicationFactor);
    }

    @Bean
    public NewTopic merchantSnapshotsDeadLetterTopic(@Value("${hpg.kafka.replication-factor:1}") short factor) {
        return HpgTopics.deadLetter(Topics.MERCHANT_SNAPSHOTS, factor);
    }
}
