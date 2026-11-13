package com.hpg.notification.infrastructure.persistence;

import com.hpg.notification.domain.Notification;
import com.hpg.notification.domain.NotificationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Optional<Notification> findBySourceEventIdAndChannelAndRecipientType(UUID sourceEventId,
            com.hpg.notification.domain.Channel channel, com.hpg.notification.domain.RecipientType recipientType);

    Page<Notification> findByStatus(NotificationStatus status, Pageable pageable);

    Page<Notification> findByMerchantId(UUID merchantId, Pageable pageable);

    List<Notification> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);

    long countByStatus(NotificationStatus status);

    /**
     * Ids of notifications whose next attempt is due. Ids only: each is then delivered in its own short
     * transaction, so one slow hospital endpoint cannot hold a transaction open for the whole batch.
     */
    @Query("""
            select n.id from Notification n
            where n.status in (com.hpg.notification.domain.NotificationStatus.PENDING,
                               com.hpg.notification.domain.NotificationStatus.RETRYING)
              and n.nextAttemptAt <= :now
            order by n.nextAttemptAt""")
    List<UUID> findDueIds(@Param("now") Instant now, Pageable pageable);

    /**
     * Notifications stuck in SENDING: the dispatcher claimed them and then the process died. Picking
     * them up again can send a hospital a duplicate, which is why every webhook carries an event id the
     * hospital can deduplicate on. Losing the notification would be worse.
     */
    @Query("""
            select n.id from Notification n
            where n.status = com.hpg.notification.domain.NotificationStatus.SENDING
              and n.updatedAt < :before
            order by n.updatedAt""")
    List<UUID> findStuckSendingIds(@Param("before") Instant before, Pageable pageable);
}
