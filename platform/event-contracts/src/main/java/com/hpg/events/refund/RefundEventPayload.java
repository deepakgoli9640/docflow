package com.hpg.events.refund;

import com.hpg.common.money.Money;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload of every {@code refund.events.v1} event (version 1). {@code status} says what happened.
 *
 * @param refundedTotal sum of all COMPLETED refunds of the payment after this event
 */
public record RefundEventPayload(
        UUID refundId,
        UUID paymentId,
        UUID merchantId,
        String merchantReference,
        Money amount,
        Money refundedTotal,
        String status,
        String reason,
        String providerRefundId,
        String failureCode,
        String failureReason,
        Instant occurredAt) {
}
