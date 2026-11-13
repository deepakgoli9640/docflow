package com.hpg.refund.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class RefundStatusTest {

    @ParameterizedTest
    @CsvSource({
            "CREATED, PROCESSING, true", "CREATED, COMPLETED, true", "CREATED, FAILED, true", "CREATED, UNKNOWN, true",
            "UNKNOWN, CREATED, true", "UNKNOWN, PROCESSING, true", "UNKNOWN, COMPLETED, true", "UNKNOWN, FAILED, true",
            "PROCESSING, COMPLETED, true", "PROCESSING, FAILED, true",
            "PROCESSING, CREATED, false", "PROCESSING, UNKNOWN, false", "CREATED, CREATED, false",
            "COMPLETED, FAILED, false", "FAILED, COMPLETED, false", "COMPLETED, PROCESSING, false"})
    void transitions(RefundStatus from, RefundStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @ParameterizedTest
    @EnumSource(value = RefundStatus.class, names = {"COMPLETED", "FAILED"})
    void terminalStatesAreFinal(RefundStatus terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        for (RefundStatus target : RefundStatus.values()) {
            assertThat(terminal.canTransitionTo(target)).isFalse();
        }
    }
}
