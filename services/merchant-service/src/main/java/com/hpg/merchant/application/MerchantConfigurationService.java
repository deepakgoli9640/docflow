package com.hpg.merchant.application;

import com.hpg.common.error.BusinessException;
import com.hpg.common.error.NotFoundException;
import com.hpg.common.money.Money;
import com.hpg.merchant.domain.AuditAction;
import com.hpg.merchant.domain.CaptureMode;
import com.hpg.merchant.domain.MerchantConfiguration;
import com.hpg.merchant.domain.MerchantErrorCode;
import com.hpg.merchant.domain.PaymentMethod;
import com.hpg.merchant.domain.PaymentSettings;
import com.hpg.merchant.infrastructure.config.MerchantProperties;
import com.hpg.merchant.infrastructure.persistence.MerchantConfigurationRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Reads and validates merchant payment configuration. */
@Service
public class MerchantConfigurationService {

    private final MerchantConfigurationRepository configurations;
    private final MerchantLookup lookup;
    private final MerchantProperties properties;
    private final WebhookUrlValidator webhookUrlValidator;
    private final AuditLogger audit;
    private final MerchantSnapshots snapshots;

    public MerchantConfigurationService(MerchantConfigurationRepository configurations, MerchantLookup lookup,
            MerchantProperties properties, AuditLogger audit, MerchantSnapshots snapshots) {
        this.configurations = configurations;
        this.lookup = lookup;
        this.properties = properties;
        this.webhookUrlValidator = new WebhookUrlValidator(properties.allowInsecureWebhooks());
        this.audit = audit;
        this.snapshots = snapshots;
    }

    @PreAuthorize("hasRole('MERCHANT')")
    @Transactional(readOnly = true)
    public MerchantConfiguration get(UUID merchantId) {
        return require(merchantId);
    }

    /** OPS view of any merchant's configuration (the webhook secret itself is never returned). */
    @PreAuthorize("hasRole('OPS')")
    @Transactional(readOnly = true)
    public MerchantConfiguration getForOps(UUID merchantId) {
        return require(merchantId);
    }

    /** Full replacement (PUT semantics) after validating against the platform maximums. */
    @PreAuthorize("hasRole('MERCHANT')")
    @Transactional
    public MerchantConfiguration update(UUID merchantId, PaymentSettings settings) {
        var merchant = lookup.require(merchantId);
        merchant.requireNotDeactivated();
        validate(settings);
        MerchantConfiguration configuration = require(merchantId);
        configuration.apply(settings);
        audit.record(merchantId, AuditAction.CONFIGURATION_UPDATED, Map.of(
                "captureMode", settings.captureMode().name(),
                "enabledMethods", settings.enabledMethods().stream().map(Enum::name).sorted().toList(),
                "maxAmountPerTransaction", settings.maxAmountPerTransaction().amount().toPlainString(),
                "dailyLimit", settings.dailyLimit().amount().toPlainString(),
                "webhookConfigured", settings.webhookUrl() != null));
        snapshots.publish(merchant);
        return configuration;
    }

    /** Returns the new secret once; afterwards it can only be read (decrypted) by notification-service. */
    @PreAuthorize("hasRole('MERCHANT')")
    @Transactional
    public String rotateWebhookSecret(UUID merchantId) {
        lookup.require(merchantId).requireNotDeactivated();
        String secret = WebhookSecrets.generate();
        require(merchantId).rotateWebhookSecret(secret);
        audit.record(merchantId, AuditAction.WEBHOOK_SECRET_ROTATED, Map.of());
        return secret;
    }

    /** For notification-service only (SERVICE role): what it needs to deliver and sign webhooks. */
    @PreAuthorize("hasRole('SERVICE')")
    @Transactional(readOnly = true)
    public WebhookConfig webhookConfig(UUID merchantId) {
        var merchant = lookup.require(merchantId);
        MerchantConfiguration configuration = require(merchantId);
        return new WebhookConfig(merchantId, merchant.getStatus(), configuration.getWebhookUrl(),
                configuration.getWebhookSecret());
    }

    PaymentSettings defaultSettings() {
        MerchantProperties.Defaults d = properties.defaults();
        String currency = properties.currency();
        return new PaymentSettings(
                d.enabledMethods().stream().map(PaymentMethod::valueOf)
                        .collect(Collectors.toCollection(() -> EnumSet.noneOf(PaymentMethod.class))),
                CaptureMode.valueOf(d.captureMode()),
                new Money(d.minAmount(), currency),
                new Money(d.maxAmountPerTransaction(), currency),
                new Money(d.dailyLimit(), currency),
                d.paymentTtlMinutes(), d.maxAttempts(), d.refundWindowDays(), null,
                d.notifyPatientEmail(), d.notifyPatientSms());
    }

    void validate(PaymentSettings s) {
        String currency = properties.currency();
        for (Money m : new Money[] {s.minAmount(), s.maxAmountPerTransaction(), s.dailyLimit()}) {
            if (!m.currency().equals(currency)) {
                throw new BusinessException(MerchantErrorCode.UNSUPPORTED_CURRENCY,
                        "Only " + currency + " is supported.", Map.of("supportedCurrencies", java.util.List.of(currency)));
            }
        }
        MerchantProperties.Limits limits = properties.limits();
        requireAtMost("maxAmountPerTransaction", s.maxAmountPerTransaction(), new Money(limits.maxAmountPerTransaction(), currency));
        requireAtMost("dailyLimit", s.dailyLimit(), new Money(limits.maxDailyLimit(), currency));
        if (s.minAmount().isLessThan(new Money(limits.minAmountFloor(), currency))) {
            throw new BusinessException(MerchantErrorCode.INVALID_LIMITS,
                    "minAmount must be at least " + limits.minAmountFloor().toPlainString() + " " + currency + ".");
        }
        if (s.minAmount().isGreaterThan(s.maxAmountPerTransaction())
                || s.maxAmountPerTransaction().isGreaterThan(s.dailyLimit())) {
            throw new BusinessException(MerchantErrorCode.INVALID_LIMITS,
                    "Limits must satisfy minAmount <= maxAmountPerTransaction <= dailyLimit.");
        }
        requireRange("paymentTtlMinutes", s.paymentTtlMinutes(), limits.minPaymentTtlMinutes(), limits.maxPaymentTtlMinutes());
        requireRange("maxAttempts", s.maxAttempts(), 1, limits.maxAttempts());
        requireRange("refundWindowDays", s.refundWindowDays(), 1, limits.maxRefundWindowDays());
        webhookUrlValidator.validate(s.webhookUrl());
    }

    private static void requireAtMost(String field, Money value, Money maximum) {
        if (value.isGreaterThan(maximum)) {
            throw new BusinessException(MerchantErrorCode.LIMIT_EXCEEDS_PLATFORM_MAXIMUM,
                    field + " exceeds the platform maximum of " + maximum + ".",
                    Map.of("field", field, "platformMaximum", maximum));
        }
    }

    private static void requireRange(String field, int value, int min, int max) {
        if (value < min || value > max) {
            throw new BusinessException(MerchantErrorCode.LIMIT_EXCEEDS_PLATFORM_MAXIMUM,
                    field + " must be between " + min + " and " + max + ".",
                    Map.of("field", field, "min", min, "max", max));
        }
    }

    private MerchantConfiguration require(UUID merchantId) {
        return configurations.findById(merchantId).orElseThrow(() -> new NotFoundException(
                MerchantErrorCode.MERCHANT_NOT_FOUND, "Merchant " + merchantId + " not found."));
    }
}
