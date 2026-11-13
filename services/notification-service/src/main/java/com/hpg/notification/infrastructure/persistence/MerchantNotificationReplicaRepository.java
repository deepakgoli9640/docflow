package com.hpg.notification.infrastructure.persistence;

import com.hpg.notification.domain.MerchantNotificationReplica;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MerchantNotificationReplicaRepository extends JpaRepository<MerchantNotificationReplica, UUID> {
}
