package com.hpg.payment.provider;

import java.time.Instant;

/**
 * A verified, parsed provider webhook. Payment events carry {@code payment}; dispute events carry
 * {@code dispute} (and usually the payment too).
 */
public record ProviderWebhookEvent(String eventId, String eventType, Instant createdAt, ProviderPayment payment,
        ProviderDispute dispute) {

    public ProviderWebhookEvent(String eventId, String eventType, Instant createdAt, ProviderPayment payment) {
        this(eventId, eventType, createdAt, payment, null);
    }

    public boolean isDispute() {
        return dispute != null;
    }
}
