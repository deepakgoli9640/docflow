package com.hpg.merchant.infrastructure.persistence;

import com.hpg.merchant.domain.MerchantAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MerchantAuditLogRepository extends JpaRepository<MerchantAuditLog, Long> {

    List<MerchantAuditLog> findByMerchantIdOrderByIdAsc(UUID merchantId);
}
