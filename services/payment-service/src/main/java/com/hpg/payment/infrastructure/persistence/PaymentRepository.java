package com.hpg.payment.infrastructure.persistence;

import com.hpg.payment.domain.Payment;
import com.hpg.payment.domain.PaymentStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID>, JpaSpecificationExecutor<Payment> {

    /** Every merchant-facing read goes through this: a payment of another merchant is simply "not found". */
    Optional<Payment> findByIdAndMerchantId(UUID id, UUID merchantId);

    Optional<Payment> findByCheckoutToken(String checkoutToken);

    @Query("""
            select p from Payment p
            where p.merchantId = :merchantId and p.merchantReference = :reference
              and p.status not in (com.hpg.payment.domain.PaymentStatus.FAILED,
                                   com.hpg.payment.domain.PaymentStatus.CANCELLED,
                                   com.hpg.payment.domain.PaymentStatus.EXPIRED)""")
    Optional<Payment> findLiveByMerchantReference(@Param("merchantId") UUID merchantId,
            @Param("reference") String reference);

    /** Amount already committed today: payments that are not failed, cancelled or expired. */
    @Query(value = """
            SELECT COALESCE(SUM(amount), 0) FROM payments
            WHERE merchant_id = :merchantId AND created_at >= :since
              AND status NOT IN ('FAILED', 'CANCELLED', 'EXPIRED')""", nativeQuery = true)
    BigDecimal sumCommittedAmountSince(@Param("merchantId") UUID merchantId, @Param("since") Instant since);

    // ---- recovery scans: return ids only; each payment is then processed in its own transaction ----

    @Query("select p.id from Payment p where p.status = :status and p.updatedAt < :before order by p.updatedAt")
    List<UUID> findIdsByStatusUpdatedBefore(@Param("status") PaymentStatus status, @Param("before") Instant before,
            Pageable page);

    @Query("""
            select p.id from Payment p where p.status = com.hpg.payment.domain.PaymentStatus.PENDING
              and coalesce(p.lastVerifiedAt, p.createdAt) < :before and p.expiresAt > :now
            order by p.createdAt""")
    List<UUID> findPendingIdsToVerify(@Param("before") Instant before, @Param("now") Instant now, Pageable page);

    @Query("select p.id from Payment p where p.status in :statuses and p.expiresAt < :now order by p.expiresAt")
    List<UUID> findExpiredIds(@Param("statuses") Collection<PaymentStatus> statuses, @Param("now") Instant now,
            Pageable page);

    @Query("""
            select p.id from Payment p where p.status = com.hpg.payment.domain.PaymentStatus.AUTHORIZED
              and p.authorizedAt < :before order by p.authorizedAt""")
    List<UUID> findAuthorizedIdsBefore(@Param("before") Instant before, Pageable page);
}
