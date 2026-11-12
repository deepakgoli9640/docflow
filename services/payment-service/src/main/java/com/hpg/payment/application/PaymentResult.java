package com.hpg.payment.application;

import com.hpg.payment.domain.Payment;

/**
 * @param payment        current state of the payment
 * @param httpStatus     status code to answer with (201 on creation, 200 or 202 otherwise)
 * @param replayed       true when this answers a repeated Idempotency-Key
 */
public record PaymentResult(Payment payment, int httpStatus, boolean replayed) {
}
