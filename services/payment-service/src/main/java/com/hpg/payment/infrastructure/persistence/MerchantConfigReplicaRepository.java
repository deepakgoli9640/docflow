package com.hpg.payment.infrastructure.persistence;

import com.hpg.payment.domain.MerchantConfigReplica;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MerchantConfigReplicaRepository extends JpaRepository<MerchantConfigReplica, UUID> {
}
