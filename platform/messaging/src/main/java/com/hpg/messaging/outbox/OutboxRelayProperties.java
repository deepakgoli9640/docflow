package com.hpg.messaging.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param enabled     start the relay (tests without Kafka switch it off)
 * @param interval    pause between rounds when the outbox is drained
 * @param batchSize   rows per transaction
 * @param sendTimeout how long to wait for Kafka's acknowledgement of one record
 * @param retention   how long published rows are kept before cleanup
 */
@ConfigurationProperties("hpg.messaging.outbox.relay")
public record OutboxRelayProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("500ms") Duration interval,
        @DefaultValue("100") int batchSize,
        @DefaultValue("10s") Duration sendTimeout,
        @DefaultValue("7d") Duration retention) {
}
