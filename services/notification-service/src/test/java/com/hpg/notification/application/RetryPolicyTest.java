package com.hpg.notification.application;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    private final Instant now = Instant.parse("2026-11-13T10:00:00Z");
    private final RetryPolicy policy = new RetryPolicy(5, Duration.ofSeconds(30), 3.0, Duration.ofHours(2));

    @Test
    void delaysGrowButStayWithinTheCap() {
        // Jitter means an exact value cannot be asserted, so each attempt is checked against its window.
        assertThat(delay(1)).isBetween(15L, 30L);           // 30s, jittered
        assertThat(delay(2)).isBetween(45L, 90L);           // 90s
        assertThat(delay(3)).isBetween(135L, 270L);         // 270s
        assertThat(delay(4)).isBetween(405L, 810L);         // 810s
    }

    @Test
    void neverWaitsLongerThanTheCap() {
        RetryPolicy aggressive = new RetryPolicy(20, Duration.ofSeconds(30), 10.0, Duration.ofMinutes(10));
        for (int attempt = 1; attempt < 20; attempt++) {
            assertThat(aggressive.nextAttemptAt(attempt, now)).isBeforeOrEqualTo(now.plus(Duration.ofMinutes(10)));
        }
    }

    @Test
    void jitterActuallyVariesTheDelay() {
        // Without jitter every notification that failed during one outage would retry in the same instant.
        long distinct = java.util.stream.IntStream.range(0, 50)
                .mapToLong(i -> delay(3))
                .distinct().count();
        assertThat(distinct).isGreaterThan(5);
    }

    @Test
    void returnsNullWhenTheBudgetIsSpent() {
        assertThat(policy.nextAttemptAt(5, now)).isNull();
        assertThat(policy.nextAttemptAt(6, now)).isNull();
        assertThat(policy.maxAttempts()).isEqualTo(5);
    }

    private long delay(int attemptsMade) {
        return Duration.between(now, policy.nextAttemptAt(attemptsMade, now)).toSeconds();
    }
}
