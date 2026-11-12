package com.hpg.merchant.domain;

import com.hpg.persistence.AuditableEntity;
import com.hpg.common.id.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * An API key pair: public {@code keyId} + secret. Only a bcrypt hash of the secret is stored, so a
 * database leak does not reveal usable credentials. The plaintext secret exists only in the response
 * to the request that created it.
 */
@Entity
@Table(name = "merchant_credentials")
public class MerchantCredential extends AuditableEntity {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "key_id", nullable = false, length = 40)
    private String keyId;

    @Column(name = "secret_hash", nullable = false, length = 100)
    private String secretHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CredentialStatus status;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by", length = 100)
    private String revokedBy;

    protected MerchantCredential() {
        // for JPA
    }

    public MerchantCredential(UUID merchantId, String keyId, String secretHash) {
        this.id = Ids.newId();
        this.merchantId = merchantId;
        this.keyId = keyId;
        this.secretHash = secretHash;
        this.status = CredentialStatus.ACTIVE;
    }

    public void revoke(Instant when, String actor) {
        if (status == CredentialStatus.REVOKED) {
            return; // idempotent: revoking twice is not an error
        }
        this.status = CredentialStatus.REVOKED;
        this.revokedAt = when;
        this.revokedBy = actor;
    }

    public boolean isUsableAt(Instant now) {
        return status == CredentialStatus.ACTIVE && (expiresAt == null || expiresAt.isAfter(now));
    }

    public void markUsed(Instant when) {
        this.lastUsedAt = when;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getKeyId() {
        return keyId;
    }

    public String getSecretHash() {
        return secretHash;
    }

    public CredentialStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
