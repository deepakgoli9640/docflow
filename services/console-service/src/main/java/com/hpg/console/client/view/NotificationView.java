package com.hpg.console.client.view;

import java.time.Instant;
import java.util.List;

public record NotificationView(String id, String sourceEventId, String eventType, String channel,
        String recipientType, String recipient, String merchantId, String paymentId, long sequenceNo,
        String status, int attemptCount, Instant nextAttemptAt, Instant deliveredAt, String lastError,
        Instant createdAt, Instant updatedAt) {

    public boolean requeueable() {
        return "DEAD".equals(status);
    }

    public boolean inFlight() {
        return List.of("PENDING", "SENDING", "RETRYING").contains(status);
    }
}
