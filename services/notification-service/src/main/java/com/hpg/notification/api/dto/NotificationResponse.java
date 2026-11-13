package com.hpg.notification.api.dto;

import com.hpg.common.masking.Masking;
import com.hpg.notification.domain.Channel;
import com.hpg.notification.domain.Notification;
import com.hpg.notification.domain.NotificationStatus;
import com.hpg.notification.domain.RecipientType;

import java.time.Instant;
import java.util.UUID;

/**
 * A notification as operators see it. The recipient is masked: support staff need to know <em>which</em>
 * address was used, not the address itself, and this response ends up in tickets and screenshots.
 */
public record NotificationResponse(
        UUID id,
        UUID sourceEventId,
        String eventType,
        Channel channel,
        RecipientType recipientType,
        String recipient,
        UUID merchantId,
        UUID paymentId,
        long sequenceNo,
        NotificationStatus status,
        int attemptCount,
        Instant nextAttemptAt,
        Instant deliveredAt,
        String lastError,
        Instant createdAt,
        Instant updatedAt) {

    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(n.getId(), n.getSourceEventId(), n.getEventType(), n.getChannel(),
                n.getRecipientType(), mask(n), n.getMerchantId(), n.getPaymentId(), n.getSequenceNo(), n.getStatus(),
                n.getAttemptCount(), n.getNextAttemptAt(), n.getDeliveredAt(), n.getLastError(), n.getCreatedAt(),
                n.getUpdatedAt());
    }

    private static String mask(Notification n) {
        String recipient = n.getRecipient();
        if (recipient == null) {
            return null;
        }
        return switch (n.getChannel()) {
            case EMAIL -> Masking.email(recipient);
            case SMS -> Masking.phone(recipient);
            case WEBHOOK -> recipient; // a hospital's own endpoint, not personal data
        };
    }
}
