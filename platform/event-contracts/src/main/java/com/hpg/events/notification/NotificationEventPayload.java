package com.hpg.events.notification;

import java.time.Instant;
import java.util.UUID;

/** Payload of {@code NotificationFailed} on {@code notification.events.v1}: a notification gave up. */
public record NotificationEventPayload(
        UUID notificationId,
        UUID merchantId,
        UUID sourceEventId,
        String sourceEventType,
        String channel,
        int attempts,
        String lastError,
        Instant occurredAt) {

    public static final String NOTIFICATION_FAILED = "NotificationFailed";
    public static final int CURRENT_VERSION = 1;
}
