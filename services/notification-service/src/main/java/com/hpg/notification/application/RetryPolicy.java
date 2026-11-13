package com.hpg.notification.application;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * When to try a failed delivery again.
 *
 * <p>Exponential backoff gives a struggling endpoint room to recover instead of being hammered.
 * The jitter matters as much: when a hospital's endpoint comes back after an outage, every notification
 * that failed during it is due at once, and without jitter they would all arrive in the same instant and
 * knock it over again. The delay is drawn from the upper half of the interval, so it still grows but is
 * spread out.
 */
public class RetryPolicy {

    private final int maxAttempts;
    private final Duration initial;
    private final double multiplier;
    private final Duration max;

    public RetryPolicy(int maxAttempts, Duration initial, double multiplier, Duration max) {
        this.maxAttempts = maxAttempts;
        this.initial = initial;
        this.multiplier = multiplier;
        this.max = max;
    }

    /**
     * @param attemptsMade attempts already made, including the one that just failed
     * @return when to try again, or {@code null} if the budget is spent and the notification is dead
     */
    public Instant nextAttemptAt(int attemptsMade, Instant now) {
        if (attemptsMade >= maxAttempts) {
            return null;
        }
        double millis = initial.toMillis() * Math.pow(multiplier, attemptsMade - 1.0);
        long capped = (long) Math.min(millis, max.toMillis());
        long jittered = capped <= 1 ? capped : ThreadLocalRandom.current().nextLong(capped / 2, capped + 1);
        return now.plusMillis(jittered);
    }

    public int maxAttempts() {
        return maxAttempts;
    }
}
