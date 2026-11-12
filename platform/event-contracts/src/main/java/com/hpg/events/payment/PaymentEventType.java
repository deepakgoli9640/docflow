package com.hpg.events.payment;

/**
 * Event types published on {@code payment.events.v1}. The brief's suggested topics map here:
 * payment.initiated -> INITIATED, payment.authorized -> AUTHORIZED, payment.captured and
 * payment.success -> CAPTURED (docs/02-payment-lifecycle.md §3.1), payment.failed -> FAILED.
 */
public enum PaymentEventType {
    PaymentInitiated,
    PaymentAuthorized,
    PaymentCaptured,
    PaymentFailed,
    PaymentCancelled,
    PaymentExpired;

    public static final int CURRENT_VERSION = 1;
}
