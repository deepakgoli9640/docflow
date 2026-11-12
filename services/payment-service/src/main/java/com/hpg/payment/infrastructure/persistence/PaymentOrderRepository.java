package com.hpg.payment.infrastructure.persistence;

import com.hpg.payment.domain.PaymentOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, UUID> {

    Optional<PaymentOrder> findFirstByPaymentIdOrderByCreatedAtDesc(UUID paymentId);

    Optional<PaymentOrder> findByProviderAndProviderOrderId(String provider, String providerOrderId);

    List<PaymentOrder> findByPaymentId(UUID paymentId);
}
