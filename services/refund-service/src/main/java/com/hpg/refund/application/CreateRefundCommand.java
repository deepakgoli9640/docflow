package com.hpg.refund.application;

import com.hpg.common.money.Money;

import java.util.UUID;

public record CreateRefundCommand(UUID merchantId, UUID paymentId, Money amount, String reason) {
}
