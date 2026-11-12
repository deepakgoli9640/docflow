package com.hpg.payment.provider;

import java.time.Instant;

/** A verified, parsed provider webhook that carries a payment. */
public record ProviderWebhookEvent(String eventId, String eventType, Instant createdAt, ProviderPayment payment) {
}
