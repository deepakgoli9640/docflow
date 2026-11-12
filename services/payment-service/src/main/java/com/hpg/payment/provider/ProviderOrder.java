package com.hpg.payment.provider;

import com.hpg.common.money.Money;

public record ProviderOrder(String providerOrderId, Money amount, String status) {
}
