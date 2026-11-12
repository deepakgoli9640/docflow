package com.hpg.merchant.infrastructure.persistence;

import com.hpg.merchant.domain.CredentialStatus;
import com.hpg.merchant.domain.MerchantCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantCredentialRepository extends JpaRepository<MerchantCredential, UUID> {

    Optional<MerchantCredential> findByKeyId(String keyId);

    Optional<MerchantCredential> findByMerchantIdAndKeyId(UUID merchantId, String keyId);

    List<MerchantCredential> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId);

    long countByMerchantIdAndStatus(UUID merchantId, CredentialStatus status);
}
