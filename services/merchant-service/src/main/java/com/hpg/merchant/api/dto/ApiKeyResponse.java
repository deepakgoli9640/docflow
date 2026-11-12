package com.hpg.merchant.api.dto;

import com.hpg.merchant.domain.CredentialStatus;
import com.hpg.merchant.domain.MerchantCredential;

import java.time.Instant;

/** Listing view of a key. The secret is never returned after creation. */
public record ApiKeyResponse(String keyId, CredentialStatus status, Instant createdAt, Instant lastUsedAt,
        Instant revokedAt) {

    public static ApiKeyResponse from(MerchantCredential c) {
        return new ApiKeyResponse(c.getKeyId(), c.getStatus(), c.getCreatedAt(), c.getLastUsedAt(), c.getRevokedAt());
    }
}
