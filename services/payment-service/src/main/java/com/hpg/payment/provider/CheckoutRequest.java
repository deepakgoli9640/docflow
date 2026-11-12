package com.hpg.payment.provider;

import com.hpg.common.money.Money;

import java.util.Set;

/** Input for building the hosted checkout. Patient details are only used to prefill the form. */
public record CheckoutRequest(String providerOrderId, Money amount, String merchantName, String description,
        String customerName, String customerEmail, String customerPhone, Set<String> enabledMethods,
        String callbackUrl) {

    @Override
    public String toString() {
        return "CheckoutRequest[order=" + providerOrderId + ", amount=" + amount + ", customer=****]";
    }
}
