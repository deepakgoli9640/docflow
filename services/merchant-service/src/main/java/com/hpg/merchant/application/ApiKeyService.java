package com.hpg.merchant.application;

import com.hpg.common.crypto.SecureTokens;
import com.hpg.common.error.BusinessException;
import com.hpg.common.error.NotFoundException;
import com.hpg.merchant.domain.AuditAction;
import com.hpg.merchant.domain.CredentialStatus;
import com.hpg.merchant.domain.Merchant;
import com.hpg.merchant.domain.MerchantCredential;
import com.hpg.merchant.domain.MerchantErrorCode;
import com.hpg.merchant.infrastructure.config.MerchantProperties;
import com.hpg.persistence.Actors;
import com.hpg.merchant.infrastructure.persistence.MerchantCredentialRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * API key lifecycle. A key is the client id/secret pair a hospital uses to obtain access tokens.
 *
 * <p>Key ids look like {@code hpgk_7Hq2...} (public, safe to log). Secrets are 256 random bits, hashed
 * with bcrypt and shown exactly once.
 */
@Service
public class ApiKeyService {

    static final String KEY_ID_PREFIX = "hpgk_";

    private final MerchantCredentialRepository credentials;
    private final MerchantLookup lookup;
    private final PasswordEncoder passwordEncoder;
    private final MerchantProperties properties;
    private final AuditLogger audit;
    private final Clock clock;

    public ApiKeyService(MerchantCredentialRepository credentials, MerchantLookup lookup,
            PasswordEncoder passwordEncoder, MerchantProperties properties, AuditLogger audit, Clock clock) {
        this.credentials = credentials;
        this.lookup = lookup;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
    }

    /** Called inside registration's transaction; no limit check is needed for a brand-new merchant. */
    @Transactional(propagation = Propagation.MANDATORY)
    IssuedApiKey issueForNewMerchant(UUID merchantId) {
        return issue(merchantId);
    }

    @PreAuthorize("hasRole('MERCHANT')")
    @Transactional
    public IssuedApiKey create(UUID merchantId) {
        // Lock the merchant row so two concurrent "create key" calls cannot both pass the limit check.
        Merchant merchant = lookup.requireForUpdate(merchantId);
        merchant.requireNotDeactivated();
        long active = credentials.countByMerchantIdAndStatus(merchantId, CredentialStatus.ACTIVE);
        if (active >= properties.maxActiveApiKeys()) {
            throw new BusinessException(MerchantErrorCode.API_KEY_LIMIT_REACHED,
                    "A merchant can have at most " + properties.maxActiveApiKeys()
                            + " active API keys. Revoke an old key first.");
        }
        IssuedApiKey key = issue(merchantId);
        audit.record(merchantId, AuditAction.API_KEY_CREATED, Map.of("apiKeyId", key.keyId()));
        return key;
    }

    @PreAuthorize("hasRole('MERCHANT')")
    @Transactional(readOnly = true)
    public List<MerchantCredential> list(UUID merchantId) {
        return credentials.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }

    /**
     * Revoking blocks new tokens for the key at once. A merchant cannot revoke its last active key:
     * it would lock itself out with no way to create a new one.
     */
    @PreAuthorize("hasRole('MERCHANT')")
    @Transactional
    public void revoke(UUID merchantId, String keyId) {
        lookup.requireForUpdate(merchantId);
        MerchantCredential credential = credentials.findByMerchantIdAndKeyId(merchantId, keyId)
                .orElseThrow(() -> new NotFoundException(MerchantErrorCode.API_KEY_NOT_FOUND,
                        "API key " + keyId + " not found."));
        if (credential.getStatus() == CredentialStatus.REVOKED) {
            return;
        }
        if (credentials.countByMerchantIdAndStatus(merchantId, CredentialStatus.ACTIVE) <= 1) {
            throw new BusinessException(MerchantErrorCode.LAST_ACTIVE_API_KEY,
                    "Create a replacement key before revoking the last active one.");
        }
        credential.revoke(clock.instant(), Actors.current());
        audit.record(merchantId, AuditAction.API_KEY_REVOKED, Map.of("apiKeyId", keyId));
    }

    /** Lookup used by the token endpoint. Not secured by method security: it runs before authentication. */
    @Transactional(readOnly = true)
    public Optional<ClientCredential> findClient(String keyId) {
        return credentials.findByKeyId(keyId).flatMap(credential -> {
            Merchant merchant = lookup.require(credential.getMerchantId());
            boolean usable = credential.isUsableAt(clock.instant()) && merchant.getStatus().allowsLogin();
            return usable ? Optional.of(new ClientCredential(credential.getId(), credential.getKeyId(),
                    credential.getSecretHash(), merchant.getId())) : Optional.empty();
        });
    }

    /** Best-effort usage tracking for spotting unused keys; runs in its own short transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUsage(String keyId) {
        credentials.findByKeyId(keyId).ifPresent(c -> c.markUsed(clock.instant()));
    }

    private IssuedApiKey issue(UUID merchantId) {
        String keyId = KEY_ID_PREFIX + SecureTokens.alphanumeric(24);
        String secret = SecureTokens.urlSafe(32);
        MerchantCredential credential = credentials.save(
                new MerchantCredential(merchantId, keyId, passwordEncoder.encode(secret)));
        return new IssuedApiKey(credential.getKeyId(), secret, clock.instant());
    }

    /** What the token endpoint needs to authenticate a client. Holds a hash, never the secret. */
    public record ClientCredential(UUID credentialId, String keyId, String secretHash, UUID merchantId) {
    }
}
