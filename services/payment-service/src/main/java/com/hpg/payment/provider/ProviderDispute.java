package com.hpg.payment.provider;

import com.hpg.common.money.Money;

import java.time.Instant;

/**
 * A dispute (chargeback) as the provider reports it.
 *
 * @param status          OPEN, UNDER_REVIEW, WON, LOST or CLOSED
 * @param amountDeducted  what the provider has taken from our balance so far for this dispute
 * @param phase           chargeback, pre_arbitration, arbitration, retrieval or fraud (provider's words)
 * @param respondBy       deadline for evidence, if any
 */
public record ProviderDispute(String providerDisputeId, String providerPaymentId, Money amount, Money amountDeducted,
        String status, String reasonCode, String phase, Instant respondBy, Instant createdAt) {
}
