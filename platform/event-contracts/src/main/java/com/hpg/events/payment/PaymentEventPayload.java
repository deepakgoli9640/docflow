package com.hpg.events.payment;

import com.hpg.common.money.Money;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload of every {@code payment.events.v1} event (version 1). One shape for all types keeps
 * consumers simple; {@code status} says what happened.
 *
 * <p>{@code customer} is present only on events whose consumers need to contact the patient
 * (captured, failed, expired); it is null otherwise. Never card data, never clinical data.
 */
public record PaymentEventPayload(
        UUID paymentId,
        UUID merchantId,
        String merchantReference,
        Money amount,
        String status,
        String previousStatus,
        String captureMode,
        String provider,
        String providerOrderId,
        String providerPaymentId,
        boolean lateCapture,
        String failureCode,
        String failureReason,
        Instant occurredAt,
        Customer customer) {

    public record Customer(String name, String email, String phone) {

        @Override
        public String toString() {
            return "Customer[****]";
        }
    }
}
