package com.hpg.refund.domain;

import com.hpg.common.money.Money;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Read model of a {@code refundable_payments} row. Amounts change only through atomic SQL updates. */
public record RefundablePayment(
        UUID paymentId,
        UUID merchantId,
        String merchantReference,
        String provider,
        String providerPaymentId,
        String currency,
        BigDecimal capturedAmount,
        BigDecimal reservedAmount,
        BigDecimal refundedAmount,
        Instant capturedAt) {

    public Money captured() {
        return new Money(capturedAmount, currency);
    }

    public Money refunded() {
        return new Money(refundedAmount, currency);
    }

    /** What may still be refunded: captured minus completed minus in flight. */
    public Money remainingRefundable() {
        return new Money(capturedAmount.subtract(refundedAmount).subtract(reservedAmount), currency);
    }
}
