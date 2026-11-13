package com.hpg.notification.infrastructure.persistence;

import com.hpg.notification.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID>, JpaSpecificationExecutor<Notification> {

    Optional<Notification> findByIdAndMerchantId(UUID id, UUID merchantId);

    List<Notification> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);
}
