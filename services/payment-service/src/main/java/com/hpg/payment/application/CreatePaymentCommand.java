package com.hpg.payment.application;

import com.hpg.common.money.Money;
import com.hpg.payment.domain.Payment;

import java.util.UUID;

public record CreatePaymentCommand(UUID merchantId, String merchantReference, Money amount, String description,
        Payment.Customer customer, String returnUrl, Integer expiresInMinutes) {
}
