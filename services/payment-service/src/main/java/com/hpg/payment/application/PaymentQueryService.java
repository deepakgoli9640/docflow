package com.hpg.payment.application;

import com.hpg.payment.domain.Payment;
import com.hpg.payment.domain.PaymentAttempt;
import com.hpg.payment.domain.PaymentStatus;
import com.hpg.payment.domain.PaymentStatusHistory;
import com.hpg.payment.infrastructure.persistence.PaymentAttemptRepository;
import com.hpg.payment.infrastructure.persistence.PaymentRepository;
import com.hpg.payment.infrastructure.persistence.PaymentStatusHistoryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class PaymentQueryService {

    private final PaymentRepository payments;
    private final PaymentAttemptRepository attempts;
    private final PaymentStatusHistoryRepository history;
    private final PaymentLookup lookup;

    public PaymentQueryService(PaymentRepository payments, PaymentAttemptRepository attempts,
            PaymentStatusHistoryRepository history, PaymentLookup lookup) {
        this.payments = payments;
        this.attempts = attempts;
        this.history = history;
        this.lookup = lookup;
    }

    public record PaymentDetails(Payment payment, List<PaymentAttempt> attempts, List<PaymentStatusHistory> history) {
    }

    @PreAuthorize("hasRole('MERCHANT')")
    @Transactional(readOnly = true)
    public PaymentDetails get(UUID merchantId, UUID paymentId) {
        Payment payment = lookup.requireOwned(merchantId, paymentId);
        return new PaymentDetails(payment, attempts.findByPaymentIdOrderByCreatedAtAsc(paymentId),
                history.findByPaymentIdOrderByIdAsc(paymentId));
    }

    @PreAuthorize("hasRole('MERCHANT')")
    @Transactional(readOnly = true)
    public Page<Payment> list(UUID merchantId, PaymentStatus status, String merchantReference, Instant from,
            Instant to, Pageable pageable) {
        Specification<Payment> spec = (root, q, cb) -> cb.equal(root.get("merchantId"), merchantId);
        if (status != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        if (merchantReference != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("merchantReference"), merchantReference));
        }
        if (from != null) {
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from));
        }
        if (to != null) {
            spec = spec.and((root, q, cb) -> cb.lessThan(root.get("createdAt"), to));
        }
        return payments.findAll(spec, pageable);
    }
}
