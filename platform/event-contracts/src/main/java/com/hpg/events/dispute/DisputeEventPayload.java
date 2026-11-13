package com.hpg.events.dispute;

import com.hpg.common.money.Money;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload of every dispute event.
 *
 * @param amount      the disputed amount
 * @param moneyMoved  for DisputeFundsDeducted / DisputeFundsReinstated: the amount deducted or returned; else null
 * @param status      OPEN, UNDER_REVIEW, WON, LOST or CLOSED after this event
 * @param respondBy   deadline for the hospital's evidence, if the provider gave one
 */
public record DisputeEventPayload(
        UUID disputeId,
        UUID paymentId,
        UUID merchantId,
        String provider,
        String providerDisputeId,
        String providerPaymentId,
        Money amount,
        Money moneyMoved,
        String status,
        String reasonCode,
        String phase,
        Instant respondBy,
        Instant occurredAt) {
}
