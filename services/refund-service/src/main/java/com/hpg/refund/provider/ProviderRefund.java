package com.hpg.refund.provider;

import com.hpg.common.money.Money;

/**
 * A refund as the provider reports it.
 *
 * @param ourRefundId our refund id, echoed back from the receipt/notes we sent (null if absent)
 */
public record ProviderRefund(
        String providerRefundId,
        String providerPaymentId,
        Money amount,
        Status status,
        String ourRefundId,
        String errorCode,
        String errorDescription) {

    public enum Status { PENDING, PROCESSED, FAILED }
}
