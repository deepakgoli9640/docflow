package com.hpg.events.payment;

import com.hpg.common.money.Money;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload of every {@code payment.events.v1} event (version 1). One shape for all types keeps
 * consumers simple; {@code status} says what happened.
 *
 * <p>{@code customer} is present only on events whose consumers need to contact the patient
 * (initiated with a checkout link to send, captured, failed, expired); it is null otherwise. Never card data,
 * never clinical data.
 *
 * <p>{@code checkoutLink} is present only on {@code PaymentInitiated} when the hospital asked HPG to send the
 * patient their payment link. Added within v1 as an optional field; older consumers ignore it.
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
        Customer customer,
        CheckoutLink checkoutLink) {

    /** Version 1 shape without a checkout link (every event except a {@code PaymentInitiated} to deliver). */
    public PaymentEventPayload(UUID paymentId, UUID merchantId, String merchantReference, Money amount, String status,
            String previousStatus, String captureMode, String provider, String providerOrderId,
            String providerPaymentId, boolean lateCapture, String failureCode, String failureReason,
            Instant occurredAt, Customer customer) {
        this(paymentId, merchantId, merchantReference, amount, status, previousStatus, captureMode, provider,
                providerOrderId, providerPaymentId, lateCapture, failureCode, failureReason, occurredAt, customer,
                null);
    }

    /**
     * The patient's hosted checkout link, for notification-service to deliver.
     *
     * @param url          public checkout URL; whoever holds it can pay this one bill, so treat it as private
     * @param expiresAt    when the link stops accepting payments
     * @param merchantName the hospital's display name, so the patient recognises who is asking
     */
    public record CheckoutLink(String url, Instant expiresAt, String merchantName) {

        @Override
        public String toString() {
            return "CheckoutLink[url=****, expiresAt=" + expiresAt + "]";
        }
    }

    public record Customer(String name, String email, String phone) {

        @Override
        public String toString() {
            return "Customer[****]";
        }
    }
}
