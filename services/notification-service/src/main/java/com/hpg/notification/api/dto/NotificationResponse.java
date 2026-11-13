package com.hpg.notification.api.dto;

import com.hpg.common.masking.Masking;
import com.hpg.notification.domain.Notification;
import com.hpg.notification.infrastructure.persistence.NotificationStore;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A notification as the hospital sees it. The patient's address is masked; bodies are not exposed. */
public record NotificationResponse(
        UUID id,
        String channel,
        String eventType,
        UUID eventId,
        UUID paymentId,
        UUID refundId,
        String recipient,
        String status,
        int attempts,
        Integer lastResponseStatus,
        String lastError,
        Instant nextAttemptAt,
        Instant deliveredAt,
        Instant createdAt,
        List<NotificationStore.Attempt> attemptLog) {

    public static NotificationResponse summary(Notification n) {
        return of(n, null);
    }

    public static NotificationResponse of(Notification n, List<NotificationStore.Attempt> attempts) {
        return new NotificationResponse(n.getId(), n.getChannel().name(), n.getPublicEventType(), n.getSourceEventId(),
                n.getPaymentId(), n.getRefundId(), n.getRecipient() == null ? null : Masking.email(n.getRecipient()),
                n.getStatus().name(), n.getAttempts(), n.getLastResponseStatus(), n.getLastError(),
                n.getStatus().name().equals("PENDING") ? n.getNextAttemptAt() : null, n.getDeliveredAt(),
                n.getCreatedAt(), attempts);
    }
}
