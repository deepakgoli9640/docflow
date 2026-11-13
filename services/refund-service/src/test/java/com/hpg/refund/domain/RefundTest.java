package com.hpg.refund.domain;

import com.hpg.common.money.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RefundTest {

    final Instant now = Instant.parse("2026-11-12T10:00:00Z");

    Refund refund() {
        return new Refund(UUID.randomUUID(), UUID.randomUUID(), Money.inr("500.00"), "partial", InitiatedBy.MERCHANT,
                "RAZORPAY", "corr-1");
    }

    @Test
    void completingSetsCompletedAt() {
        Refund r = refund();
        assertThat(r.transition(RefundStatus.COMPLETED, now)).isTrue();
        assertThat(r.getCompletedAt()).isEqualTo(now);
    }

    @Test
    void lateEvidenceForAFinalRefundIsIgnored() {
        Refund r = refund();
        r.transition(RefundStatus.FAILED, now);
        assertThat(r.transition(RefundStatus.COMPLETED, now)).isFalse();
        assertThat(r.getStatus()).isEqualTo(RefundStatus.FAILED);
    }

    @Test
    void providerRefundIdIsAdoptedOnce() {
        Refund r = refund();
        r.adoptProviderRefundId("rfnd_1");
        r.adoptProviderRefundId("rfnd_2");
        assertThat(r.getProviderRefundId()).isEqualTo("rfnd_1");
    }
}
