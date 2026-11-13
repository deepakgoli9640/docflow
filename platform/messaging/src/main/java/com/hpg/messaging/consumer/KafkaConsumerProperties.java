package com.hpg.messaging.consumer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Blocking retry for consumers: exponential backoff, then the dead-letter topic.
 *
 * @param maxRetries        retries before dead-lettering (default ~5 minutes in total with the defaults)
 * @param initialInterval   first backoff
 * @param maxInterval       cap per backoff
 * @param topicPartitions   partitions for topics this service creates (local default; production: more)
 */
@ConfigurationProperties("hpg.messaging.consumer")
public record KafkaConsumerProperties(
        @DefaultValue("12") int maxRetries,
        @DefaultValue("1s") Duration initialInterval,
        @DefaultValue("30s") Duration maxInterval,
        @DefaultValue("3") int topicPartitions) {
}
