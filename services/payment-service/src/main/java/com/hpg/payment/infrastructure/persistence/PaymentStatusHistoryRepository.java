package com.hpg.payment.infrastructure.persistence;

import com.hpg.payment.domain.PaymentStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PaymentStatusHistoryRepository extends JpaRepository<PaymentStatusHistory, Long> {

    List<PaymentStatusHistory> findByPaymentIdOrderByIdAsc(UUID paymentId);
}
