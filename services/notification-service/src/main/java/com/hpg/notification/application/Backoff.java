package com.hpg.notification.application;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/**
 * Exponential backoff with jitter: {@code initial * 2^(attempt-1)}, capped, then randomised by ±20 % so
 * that many notifications failing together (a hospital endpoint down) do not retry in lock-step.
 */
public final class Backoff {

    private final Duration initial;
    private final Duration max;
    private final DoubleSupplier random;

    public Backoff(Duration initial, Duration max, DoubleSupplier random) {
        this.initial = initial;
        this.max = max;
        this.random = random;
    }

    /** @param attempt number of attempts made so far (1 after the first failure) */
    public Duration delayAfter(int attempt) {
        int exponent = Math.min(Math.max(attempt - 1, 0), 30);
        long base = Math.min(initial.toMillis() * (1L << exponent), max.toMillis());
        double jitter = 0.8 + 0.4 * random.getAsDouble();
        return Duration.ofMillis(Math.max(1, Math.round(base * jitter)));
    }
}
