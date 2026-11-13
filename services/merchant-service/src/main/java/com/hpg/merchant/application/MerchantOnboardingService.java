package com.hpg.merchant.application;

import com.hpg.merchant.domain.AuditAction;
import com.hpg.merchant.domain.Merchant;
import com.hpg.merchant.domain.MerchantConfiguration;
import com.hpg.merchant.domain.MerchantErrorCode;
import com.hpg.merchant.domain.MerchantStatus;
import com.hpg.common.error.BusinessException;
import com.hpg.merchant.infrastructure.persistence.MerchantConfigurationRepository;
import com.hpg.merchant.infrastructure.persistence.MerchantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Registration and the merchant lifecycle (activate / suspend / deactivate). */
@Service
public class MerchantOnboardingService {

    private static final Logger log = LoggerFactory.getLogger(MerchantOnboardingService.class);

    private final MerchantRepository merchants;
    private final MerchantConfigurationRepository configurations;
    private final MerchantLookup lookup;
    private final ApiKeyService apiKeys;
    private final MerchantConfigurationService configurationService;
    private final AuditLogger audit;
    private final MerchantSnapshots snapshots;

    public MerchantOnboardingService(MerchantRepository merchants, MerchantConfigurationRepository configurations,
            MerchantLookup lookup, ApiKeyService apiKeys, MerchantConfigurationService configurationService,
            AuditLogger audit, MerchantSnapshots snapshots) {
        this.merchants = merchants;
        this.configurations = configurations;
        this.lookup = lookup;
        this.apiKeys = apiKeys;
        this.configurationService = configurationService;
        this.audit = audit;
        this.snapshots = snapshots;
    }

    /**
     * Self-registration. The merchant starts in PENDING_ACTIVATION and receives its first API key and
     * webhook secret in the response, so it can log in and configure itself while ops reviews it.
     */
    @Transactional
    public RegistrationResult register(RegisterMerchantCommand command) {
        String email = Merchant.normaliseEmail(command.contactEmail());
        if (merchants.existsByContactEmail(email)) {
            throw alreadyExists();
        }
        Merchant merchant = Merchant.register(command.legalName(), command.displayName(), email, command.contactPhone());
        try {
            // Flush now so a concurrent registration with the same email fails here, where it can be
            // translated, rather than at commit time as an anonymous constraint violation.
            merchants.saveAndFlush(merchant);
        } catch (DataIntegrityViolationException e) {
            throw alreadyExists();
        }
        String webhookSecret = WebhookSecrets.generate();
        configurations.save(new MerchantConfiguration(merchant.getId(), configurationService.defaultSettings(),
                webhookSecret));
        IssuedApiKey apiKey = apiKeys.issueForNewMerchant(merchant.getId());
        audit.record(merchant.getId(), AuditAction.MERCHANT_REGISTERED, Map.of("apiKeyId", apiKey.keyId()));
        snapshots.publish(merchant);
        log.info("Merchant registered merchantId={}", merchant.getId());
        return new RegistrationResult(merchant, apiKey, webhookSecret);
    }

    @PreAuthorize("hasRole('OPS')")
    @Transactional
    public Merchant activate(UUID merchantId, String reason) {
        return changeStatus(merchantId, MerchantStatus.ACTIVE, reason);
    }

    /**
     * Suspension blocks new tokens immediately; tokens already issued are refused by the gateway as soon
     * as it has consumed the snapshot (deny-list), and payment/refund creation is refused by their replicas.
     */
    @PreAuthorize("hasRole('OPS')")
    @Transactional
    public Merchant suspend(UUID merchantId, String reason) {
        return changeStatus(merchantId, MerchantStatus.SUSPENDED, reason);
    }

    @PreAuthorize("hasRole('OPS')")
    @Transactional
    public Merchant deactivate(UUID merchantId, String reason) {
        return changeStatus(merchantId, MerchantStatus.DEACTIVATED, reason);
    }

    @PreAuthorize("hasRole('OPS')")
    @Transactional(readOnly = true)
    public Merchant get(UUID merchantId) {
        return lookup.require(merchantId);
    }

    @PreAuthorize("hasRole('OPS')")
    @Transactional(readOnly = true)
    public Page<Merchant> list(MerchantStatus status, Pageable pageable) {
        return status == null ? merchants.findAll(pageable) : merchants.findByStatus(status, pageable);
    }

    private Merchant changeStatus(UUID merchantId, MerchantStatus target, String reason) {
        Merchant merchant = lookup.require(merchantId);
        MerchantStatus from = merchant.getStatus();
        merchant.changeStatus(target, reason);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("from", from.name());
        details.put("to", target.name());
        if (reason != null) {
            details.put("reason", reason);
        }
        audit.record(merchantId, AuditAction.MERCHANT_STATUS_CHANGED, details);
        snapshots.publish(merchant);
        log.info("Merchant status changed merchantId={} from={} to={}", merchantId, from, target);
        return merchant;
    }

    private static BusinessException alreadyExists() {
        return new BusinessException(MerchantErrorCode.MERCHANT_ALREADY_EXISTS,
                "A merchant is already registered with this contact email.");
    }
}
