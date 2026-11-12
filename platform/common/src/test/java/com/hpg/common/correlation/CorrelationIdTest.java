package com.hpg.common.correlation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdTest {

    @Test
    void keepsValidClientValue() {
        assertThat(CorrelationId.resolve("his-req-000981")).isEqualTo("his-req-000981");
    }

    @Test
    void replacesUnsafeValuesToPreventLogInjection() {
        assertThat(CorrelationId.resolve("abc\nFAKE LOG LINE")).isNotEqualTo("abc\nFAKE LOG LINE");
        assertThat(CorrelationId.resolve("x".repeat(65))).hasSize(36);
        assertThat(CorrelationId.resolve(null)).isNotBlank();
        assertThat(CorrelationId.resolve("")).isNotBlank();
    }
}
