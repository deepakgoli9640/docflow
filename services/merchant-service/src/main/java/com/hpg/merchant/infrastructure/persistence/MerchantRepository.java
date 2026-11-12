package com.hpg.merchant.infrastructure.persistence;

import com.hpg.merchant.domain.Merchant;
import com.hpg.merchant.domain.MerchantStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface MerchantRepository extends JpaRepository<Merchant, UUID> {

    boolean existsByContactEmail(String contactEmail);

    Page<Merchant> findByStatus(MerchantStatus status, Pageable pageable);

    /**
     * Row lock used when an operation must check-then-act on a merchant's children (e.g. "at most two
     * active API keys"). Optimistic locking would not help here: the merchant row itself is not changed.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Merchant m where m.id = :id")
    Optional<Merchant> findByIdForUpdate(@Param("id") UUID id);
}
