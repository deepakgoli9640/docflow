package com.hpg.payment.infrastructure.persistence;

import com.hpg.payment.domain.AttemptStatus;
import com.hpg.payment.domain.PaymentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {

    Optional<PaymentAttempt> findByProviderPaymentId(String providerPaymentId);

    List<PaymentAttempt> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);

    long countByPaymentIdAndStatus(UUID paymentId, AttemptStatus status);
}
