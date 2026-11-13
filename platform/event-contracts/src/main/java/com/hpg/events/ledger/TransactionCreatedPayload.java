package com.hpg.events.ledger;

import com.hpg.common.money.Money;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code TransactionCreated} on {@code ledger.events.v1} (key = paymentId): money was booked in the ledger.
 * reconciliation-service compares these with the provider's settlement report.
 *
 * @param transactionType   {@code PAYMENT} (capture) or {@code REFUND}
 * @param providerReference the provider's id of the money movement ({@code pay_...} / {@code rfnd_...}),
 *                          the key reconciliation matches on
 */
public record TransactionCreatedPayload(
        String transactionRef,
        UUID transactionId,
        UUID merchantId,
        String transactionType,
        UUID paymentId,
        UUID refundId,
        String provider,
        String providerReference,
        Money amount,
        Instant postedAt) {

    public static final String EVENT_TYPE = "TransactionCreated";
    public static final int CURRENT_VERSION = 1;
}
