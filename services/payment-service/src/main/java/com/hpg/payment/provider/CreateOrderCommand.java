package com.hpg.payment.provider;

import com.hpg.common.money.Money;
import com.hpg.payment.domain.CaptureMode;

import java.util.Map;
import java.util.UUID;

/** @param receipt our reference echoed back by the provider (the payment id) */
public record CreateOrderCommand(UUID paymentId, String receipt, Money amount, CaptureMode captureMode,
        Map<String, String> notes) {
}
