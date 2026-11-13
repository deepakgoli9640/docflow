package com.hpg.refund.infrastructure.persistence;

import com.hpg.refund.domain.RefundStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RefundStatusHistoryRepository extends JpaRepository<RefundStatusHistory, Long> {

    List<RefundStatusHistory> findByRefundIdOrderByIdAsc(UUID refundId);
}
