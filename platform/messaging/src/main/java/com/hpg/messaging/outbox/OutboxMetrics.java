package com.hpg.messaging.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Backlog gauges. {@code outbox.pending.oldest.age.seconds} is the one to alert on: a growing number is
 * normal under load, but an event that has been waiting minutes means publishing is stuck.
 */
public class OutboxMetrics {

    private final OutboxEventRepository repository;
    private final Clock clock;

    public OutboxMetrics(OutboxEventRepository repository, MeterRegistry meters, Clock clock) {
        this.repository = repository;
        this.clock = clock;
        Gauge.builder("outbox.pending.events", repository, OutboxEventRepository::countByPublishedAtIsNull)
                .description("Outbox events not yet published to Kafka")
                .register(meters);
        Gauge.builder("outbox.pending.oldest.age.seconds", this, OutboxMetrics::oldestPendingAgeSeconds)
                .description("Age of the oldest unpublished outbox event")
                .register(meters);
    }

    double oldestPendingAgeSeconds() {
        Instant oldest = repository.oldestUnpublishedCreatedAt();
        return oldest == null ? 0 : Duration.between(oldest, clock.instant()).toMillis() / 1000d;
    }
}
