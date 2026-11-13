package com.hpg.notification.application;

import com.hpg.common.error.BusinessException;
import com.hpg.common.error.NotFoundException;
import com.hpg.notification.domain.Notification;
import com.hpg.notification.domain.NotificationAttempt;
import com.hpg.notification.domain.NotificationErrorCode;
import com.hpg.notification.domain.NotificationStatus;
import com.hpg.notification.infrastructure.persistence.NotificationAttemptRepository;
import com.hpg.notification.infrastructure.persistence.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What operators need when a hospital says "we never got the callback": see what was attempted, and put
 * a dead notification back in the queue once the endpoint is fixed.
 */
@Service
public class NotificationAdminService {

    private static final Logger log = LoggerFactory.getLogger(NotificationAdminService.class);

    private final NotificationRepository notifications;
    private final NotificationAttemptRepository attempts;
    private final Clock clock;

    public NotificationAdminService(NotificationRepository notifications, NotificationAttemptRepository attempts,
            Clock clock) {
        this.notifications = notifications;
        this.attempts = attempts;
        this.clock = clock;
    }

    @PreAuthorize("hasRole('OPS')")
    @Transactional(readOnly = true)
    public Page<Notification> list(NotificationStatus status, UUID merchantId, Pageable pageable) {
        if (status != null) {
            return notifications.findByStatus(status, pageable);
        }
        if (merchantId != null) {
            return notifications.findByMerchantId(merchantId, pageable);
        }
        return notifications.findAll(pageable);
    }

    @PreAuthorize("hasRole('OPS')")
    @Transactional(readOnly = true)
    public Details get(UUID id) {
        Notification notification = notifications.findById(id)
                .orElseThrow(() -> new NotFoundException(NotificationErrorCode.NOTIFICATION_NOT_FOUND,
                        "Notification " + id + " not found."));
        return new Details(notification, attempts.findByNotificationIdOrderByAttemptNoAsc(id));
    }

    /** Puts a dead notification back in the queue. The attempt history is kept: nothing is rewritten. */
    @PreAuthorize("hasRole('OPS')")
    @Transactional
    public Notification requeue(UUID id) {
        Notification notification = notifications.findById(id)
                .orElseThrow(() -> new NotFoundException(NotificationErrorCode.NOTIFICATION_NOT_FOUND,
                        "Notification " + id + " not found."));
        if (notification.getStatus() != NotificationStatus.DEAD) {
            throw new BusinessException(NotificationErrorCode.NOTIFICATION_NOT_REQUEUEABLE,
                    "Only a DEAD notification can be requeued; this one is " + notification.getStatus() + ".",
                    Map.of("currentStatus", notification.getStatus().name()));
        }
        notification.requeue(clock.instant());
        log.info("Notification {} requeued by an operator after {} failed attempt(s)", id,
                notification.getAttemptCount());
        return notification;
    }

    public record Details(Notification notification, List<NotificationAttempt> attempts) {
    }
}
