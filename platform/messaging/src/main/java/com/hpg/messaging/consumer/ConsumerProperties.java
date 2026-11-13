package com.hpg.messaging.consumer;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Consumer retry settings (prefix {@code hpg.kafka.consumer}).
 *
 * <p>Retries here are <b>blocking</b>: the partition waits while a record is retried. That is deliberate.
 * These consumers maintain local replicas and post to a ledger, where order matters, and skipping ahead
 * to the next record would apply events out of order. Work that must be retried for hours (delivering a
 * webhook to a hospital) is not retried on the Kafka thread at all — it becomes a row in a table with its
 * own schedule.
 *
 * @param retryAttempts    total attempts before dead-lettering
 * @param retryInitial     first backoff interval
 * @param retryMax         cap for the backoff interval
 * @param processedRetention how long consumer-side dedupe rows are kept (topic retention plus a margin)
 */
@ConfigurationProperties(prefix = "hpg.kafka.consumer")
public record ConsumerProperties(Integer retryAttempts, Duration retryInitial, Duration retryMax,
        Duration processedRetention) {

    public ConsumerProperties {
        retryAttempts = retryAttempts == null ? 6 : retryAttempts;
        retryInitial = retryInitial == null ? Duration.ofMillis(500) : retryInitial;
        retryMax = retryMax == null ? Duration.ofSeconds(30) : retryMax;
        processedRetention = processedRetention == null ? Duration.ofDays(14) : processedRetention;
    }
}
