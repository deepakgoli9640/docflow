package com.hpg.notification.infrastructure.persistence;

import com.hpg.notification.domain.NotificationAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationAttemptRepository extends JpaRepository<NotificationAttempt, UUID> {

    List<NotificationAttempt> findByNotificationIdOrderByAttemptNoAsc(UUID notificationId);
}
