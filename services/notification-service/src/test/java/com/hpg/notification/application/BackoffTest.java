package com.hpg.notification.application;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BackoffTest {

    @Test
    void doublesUntilTheCapWithoutJitter() {
        Backoff b = new Backoff(Duration.ofSeconds(10), Duration.ofMinutes(5), () -> 0.5); // jitter factor 1.0
        assertThat(b.delayAfter(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(b.delayAfter(2)).isEqualTo(Duration.ofSeconds(20));
        assertThat(b.delayAfter(4)).isEqualTo(Duration.ofSeconds(80));
        assertThat(b.delayAfter(6)).isEqualTo(Duration.ofMinutes(5));
        assertThat(b.delayAfter(60)).isEqualTo(Duration.ofMinutes(5)); // no overflow
    }

    @Test
    void jitterStaysWithinPlusMinusTwentyPercent() {
        assertThat(new Backoff(Duration.ofSeconds(10), Duration.ofHours(1), () -> 0.0).delayAfter(1))
                .isEqualTo(Duration.ofSeconds(8));
        assertThat(new Backoff(Duration.ofSeconds(10), Duration.ofHours(1), () -> 0.999999).delayAfter(1))
                .isEqualTo(Duration.ofSeconds(12));
    }
}
