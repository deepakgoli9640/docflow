package com.hpg.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.events.Topics;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * Consumer plumbing shared by every service that reads Kafka:
 * <ul>
 *   <li>{@link DefaultErrorHandler}: blocking retry with exponential backoff (order matters for our
 *       consumers), then the record goes to {@code <topic>.dlt}. {@link NonRetryableEventException} and
 *       deserialisation errors skip the retries.</li>
 *   <li>Topic declarations: created if missing (one topic per aggregate + its DLT).</li>
 * </ul>
 */
@AutoConfiguration(after = {KafkaAutoConfiguration.class, JdbcTemplateAutoConfiguration.class})
@ConditionalOnClass(KafkaTemplate.class)
@EnableConfigurationProperties(KafkaConsumerProperties.class)
public class KafkaMessagingAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(KafkaMessagingAutoConfiguration.class);
    private static final List<String> TOPICS = List.of(Topics.PAYMENT_EVENTS, Topics.REFUND_EVENTS,
            Topics.NOTIFICATION_EVENTS);

    @Bean
    @ConditionalOnMissingBean
    public EventParser eventParser(ObjectMapper mapper) {
        return new EventParser(mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(JdbcTemplate.class)
    public ProcessedEvents processedEvents(JdbcTemplate jdbc, Clock clock) {
        return new ProcessedEvents(jdbc, clock);
    }

    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    @ConditionalOnBean(KafkaTemplate.class)
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template,
            KafkaConsumerProperties properties) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template, (record, ex) -> {
            Throwable cause = NestedExceptionUtils.getMostSpecificCause(ex);
            log.error("Dead-lettering record topic={} partition={} offset={} key={}: {}: {}", record.topic(),
                    record.partition(), record.offset(), record.key(), cause.getClass().getName(), cause.getMessage());
            // Negative partition: let the producer choose (the DLT may have fewer partitions than the source).
            return new TopicPartition(Topics.dlt(record.topic()), -1);
        });
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(properties.maxRetries());
        backOff.setInitialInterval(properties.initialInterval().toMillis());
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(properties.maxInterval().toMillis());
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(NonRetryableEventException.class);
        return handler;
    }

    @Bean
    @ConditionalOnBean(KafkaAdmin.class)
    public KafkaAdmin.NewTopics hpgTopics(KafkaConsumerProperties properties) {
        List<org.apache.kafka.clients.admin.NewTopic> topics = new ArrayList<>();
        for (String topic : TOPICS) {
            topics.add(TopicBuilder.name(topic).partitions(properties.topicPartitions()).build());
            topics.add(TopicBuilder.name(Topics.dlt(topic)).partitions(1).build());
        }
        // Full-state snapshots: Kafka keeps only the newest per merchant, so replicas can always be rebuilt
        // from the beginning of the topic.
        topics.add(TopicBuilder.name(Topics.MERCHANT_SNAPSHOTS).partitions(properties.topicPartitions()).compact().build());
        topics.add(TopicBuilder.name(Topics.dlt(Topics.MERCHANT_SNAPSHOTS)).partitions(1).build());
        return new KafkaAdmin.NewTopics(topics.toArray(org.apache.kafka.clients.admin.NewTopic[]::new));
    }
}
