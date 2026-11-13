package com.hpg.notification.infrastructure.config;

import com.hpg.events.Topics;
import com.hpg.messaging.config.HpgTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The topic this service produces, plus dead-letter topics for the two streams it consumes. */
@Configuration
@ConditionalOnProperty(name = "spring.kafka.admin.auto-create", havingValue = "true", matchIfMissing = true)
public class KafkaTopicsConfig {

    @Bean
    public NewTopic notificationEventsTopic(@Value("${hpg.kafka.replication-factor:1}") short factor) {
        return HpgTopics.events(Topics.NOTIFICATION_EVENTS, 1, factor);
    }

    @Bean
    public NewTopic paymentEventsDeadLetterTopic(@Value("${hpg.kafka.replication-factor:1}") short factor) {
        return HpgTopics.deadLetter(Topics.PAYMENT_EVENTS, factor);
    }

    @Bean
    public NewTopic merchantSnapshotsDeadLetterTopic(@Value("${hpg.kafka.replication-factor:1}") short factor) {
        return HpgTopics.deadLetter(Topics.MERCHANT_SNAPSHOTS, factor);
    }
}
