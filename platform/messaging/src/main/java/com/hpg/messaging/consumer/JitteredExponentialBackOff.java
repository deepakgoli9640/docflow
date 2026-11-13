package com.hpg.messaging.consumer;

import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with full jitter and a maximum number of attempts.
 *
 * <p>Jitter matters when many consumers fail at the same moment — a dependency restarted, a database
 * was briefly unavailable. Without it they all retry in lock-step and hit the recovering dependency
 * together; with it the retries spread out. The delay is drawn uniformly from
 * {@code [interval/2, interval]} so backoff still grows but is never identical across instances.
 */
public class JitteredExponentialBackOff implements BackOff {

    private final long initialIntervalMillis;
    private final double multiplier;
    private final long maxIntervalMillis;
    private final int maxAttempts;

    public JitteredExponentialBackOff(long initialIntervalMillis, double multiplier, long maxIntervalMillis,
            int maxAttempts) {
        this.initialIntervalMillis = initialIntervalMillis;
        this.multiplier = multiplier;
        this.maxIntervalMillis = maxIntervalMillis;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public BackOffExecution start() {
        return new BackOffExecution() {
            private int attempt;
            private double interval = initialIntervalMillis;

            @Override
            public long nextBackOff() {
                if (++attempt > maxAttempts) {
                    return BackOffExecution.STOP;
                }
                long capped = (long) Math.min(interval, maxIntervalMillis);
                interval *= multiplier;
                return capped <= 1 ? capped : ThreadLocalRandom.current().nextLong(capped / 2, capped + 1);
            }
        };
    }
}
