package com.hpg.events.notification;

import java.time.Instant;
import java.util.UUID;

/**
 * Why a notification was given up on. Deliberately carries no recipient address: the point is which
 * merchant and which payment are affected, and an email address or phone number in a retained topic
 * would be personal data nobody downstream needs.
 */
public record NotificationFailedPayload(
        UUID notificationId,
        UUID merchantId,
        UUID paymentId,
        UUID sourceEventId,
        String sourceEventType,
        String channel,
        String recipientType,
        int attempts,
        String lastError,
        Instant failedAt) {
}
