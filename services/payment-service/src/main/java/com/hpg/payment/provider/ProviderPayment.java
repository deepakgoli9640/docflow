package com.hpg.payment.provider;

import com.hpg.common.money.Money;

/** One payment attempt as the provider sees it. */
public record ProviderPayment(
        String providerPaymentId,
        String providerOrderId,
        ProviderPaymentStatus status,
        Money amount,
        String method,
        String errorCode,
        String errorDescription) {

    public boolean isCaptured() {
        return status == ProviderPaymentStatus.CAPTURED || status == ProviderPaymentStatus.REFUNDED;
    }
}
