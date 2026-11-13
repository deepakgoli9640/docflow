package com.hpg.transaction.domain;

import com.hpg.events.payment.PaymentEventType;
import com.hpg.events.refund.RefundEventType;

import java.util.Optional;

/**
 * Status of a transaction as merchants see it. Payment statuses mirror the externally visible payment
 * events; refund statuses mirror refund events. Internal provider steps (PENDING, PROCESSING, UNKNOWN)
 * are not events and therefore not shown here; GET /api/v1/payments/{id} has the full detail.
 */
public enum TransactionStatus {
    INITIATED,
    AUTHORIZED,
    CAPTURED,
    COMPLETED,
    FAILED,
    CANCELLED,
    EXPIRED;

    public static Optional<TransactionStatus> forPaymentEvent(String eventType) {
        PaymentEventType type;
        try {
            type = PaymentEventType.valueOf(eventType);
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
        return Optional.of(switch (type) {
            case PaymentInitiated -> INITIATED;
            case PaymentAuthorized -> AUTHORIZED;
            case PaymentCaptured -> CAPTURED;
            case PaymentFailed -> FAILED;
            case PaymentCancelled -> CANCELLED;
            case PaymentExpired -> EXPIRED;
        });
    }

    public static Optional<TransactionStatus> forRefundEvent(String eventType) {
        RefundEventType type;
        try {
            type = RefundEventType.valueOf(eventType);
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
        return Optional.of(switch (type) {
            case RefundInitiated -> INITIATED;
            case RefundCompleted -> COMPLETED;
            case RefundFailed -> FAILED;
        });
    }

    /** Money moved: a captured payment or a completed refund. */
    public boolean isSettledMoney() {
        return this == CAPTURED || this == COMPLETED;
    }
}
