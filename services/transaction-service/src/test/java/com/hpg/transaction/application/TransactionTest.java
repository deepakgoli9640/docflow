package com.hpg.transaction.application;

import com.hpg.common.money.Money;
import com.hpg.transaction.domain.Transaction;
import com.hpg.transaction.domain.TransactionStatus;
import com.hpg.transaction.domain.TransactionType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionTest {

    final Instant t0 = Instant.parse("2026-11-12T09:00:00Z");

    Transaction payment() {
        return new Transaction(UUID.randomUUID(), "TXN-20261112-000001", UUID.randomUUID(), TransactionType.PAYMENT,
                UUID.randomUUID(), null, Money.inr("100.00"), TransactionStatus.INITIATED, 0, t0, t0);
    }

    @Test
    void newerEventsMoveTheStatusForward() {
        Transaction t = payment();
        assertThat(t.applyStatus(TransactionStatus.CAPTURED, 3, t0.plusSeconds(60), t0)).isTrue();
        assertThat(t.getStatus()).isEqualTo(TransactionStatus.CAPTURED);
        assertThat(t.getCompletedAt()).isEqualTo(t0.plusSeconds(60));
    }

    @Test
    void olderEventsNeverMoveItBackwards() {
        Transaction t = payment();
        t.applyStatus(TransactionStatus.CAPTURED, 3, t0.plusSeconds(60), t0);
        assertThat(t.applyStatus(TransactionStatus.AUTHORIZED, 2, t0.plusSeconds(30), t0)).isFalse();
        assertThat(t.getStatus()).isEqualTo(TransactionStatus.CAPTURED);
    }

    @Test
    void lateCaptureAfterExpiryIsApplied() {
        Transaction t = payment();
        t.applyStatus(TransactionStatus.EXPIRED, 2, t0.plusSeconds(900), t0);
        assertThat(t.applyStatus(TransactionStatus.CAPTURED, 3, t0.plusSeconds(950), t0)).isTrue();
        t.enrich(null, "RAZORPAY", "pay_1", null, null, true);
        assertThat(t.isLateCapture()).isTrue();
        assertThat(t.getStatus()).isEqualTo(TransactionStatus.CAPTURED);
    }

    @Test
    void mapsEventTypes() {
        assertThat(TransactionStatus.forPaymentEvent("PaymentCaptured")).contains(TransactionStatus.CAPTURED);
        assertThat(TransactionStatus.forRefundEvent("RefundCompleted")).contains(TransactionStatus.COMPLETED);
        assertThat(TransactionStatus.forPaymentEvent("SomethingNew")).isEmpty();
    }
}
