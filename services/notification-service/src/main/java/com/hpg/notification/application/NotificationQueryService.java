package com.hpg.notification.application;

import com.hpg.common.error.BusinessException;
import com.hpg.common.error.NotFoundException;
import com.hpg.notification.domain.Channel;
import com.hpg.notification.domain.Notification;
import com.hpg.notification.domain.NotificationErrorCode;
import com.hpg.notification.domain.NotificationStatus;
import com.hpg.notification.infrastructure.persistence.NotificationRepository;
import com.hpg.notification.infrastructure.persistence.NotificationStore;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
public class NotificationQueryService {

    private final NotificationRepository notifications;
    private final NotificationStore store;
    private final Clock clock;

    public NotificationQueryService(NotificationRepository notifications, NotificationStore store, Clock clock) {
        this.notifications = notifications;
        this.store = store;
        this.clock = clock;
    }

    public record Details(Notification notification, List<NotificationStore.Attempt> attempts) {
    }

    @Transactional(readOnly = true)
    public Page<Notification> list(UUID merchantId, UUID paymentId, NotificationStatus status, Channel channel,
            Pageable pageable) {
        Specification<Notification> spec = (root, q, cb) -> cb.equal(root.get("merchantId"), merchantId);
        if (paymentId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("paymentId"), paymentId));
        }
        if (status != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        if (channel != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("channel"), channel));
        }
        return notifications.findAll(spec, pageable);
    }

    @Transactional(readOnly = true)
    public Details get(UUID merchantId, UUID id) {
        Notification n = notifications.findByIdAndMerchantId(id, merchantId).orElseThrow(() ->
                new NotFoundException(NotificationErrorCode.NOTIFICATION_NOT_FOUND, "Notification " + id + " not found"));
        return new Details(n, store.attemptsOf(id));
    }

    /** OPS: put a DEAD notification back in the queue (e.g. the hospital fixed its endpoint). */
    @Transactional
    public Notification requeue(UUID id) {
        Notification n = notifications.findById(id).orElseThrow(() ->
                new NotFoundException(NotificationErrorCode.NOTIFICATION_NOT_FOUND, "Notification " + id + " not found"));
        if (n.getStatus() != NotificationStatus.DEAD) {
            throw new BusinessException(NotificationErrorCode.NOTIFICATION_NOT_RETRYABLE,
                    "Notification is " + n.getStatus() + "; only DEAD notifications can be re-queued.");
        }
        n.requeue(clock.instant());
        return notifications.save(n);
    }
}
