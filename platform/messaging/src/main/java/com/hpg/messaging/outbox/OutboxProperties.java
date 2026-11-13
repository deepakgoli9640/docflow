package com.hpg.messaging.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Outbox relay settings (prefix {@code hpg.outbox}).
 *
 * @param enabled     run the relay on a schedule (tests switch it off and publish on demand)
 * @param interval    how often the relay looks for unpublished events
 * @param batchSize   events published per run
 * @param sendTimeout how long to wait for the broker to acknowledge one event
 * @param retention   how long published rows are kept before cleanup (they are transport, not records)
 */
@ConfigurationProperties(prefix = "hpg.outbox")
public record OutboxProperties(boolean enabled, Duration interval, int batchSize, Duration sendTimeout,
        Duration retention) {

    public OutboxProperties {
        interval = interval == null ? Duration.ofSeconds(1) : interval;
        batchSize = batchSize <= 0 ? 100 : batchSize;
        sendTimeout = sendTimeout == null ? Duration.ofSeconds(5) : sendTimeout;
        retention = retention == null ? Duration.ofDays(7) : retention;
    }
}
