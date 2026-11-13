package com.hpg.refund.infrastructure.persistence;

import com.hpg.refund.domain.Refund;
import com.hpg.refund.domain.RefundStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundRepository extends JpaRepository<Refund, UUID>, JpaSpecificationExecutor<Refund> {

    Optional<Refund> findByIdAndMerchantId(UUID id, UUID merchantId);

    Optional<Refund> findByProviderAndProviderRefundId(String provider, String providerRefundId);

    List<Refund> findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(RefundStatus status, Instant cutoff, Limit limit);
}
