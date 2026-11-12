package com.hpg.merchant.infrastructure.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Merchant-related platform settings (application.yml, prefix {@code hpg.merchant}).
 *
 * @param currency            the only currency accepted in this version
 * @param maxActiveApiKeys    2 allows zero-downtime rotation: create new, deploy, revoke old
 * @param allowInsecureWebhooks permits http:// and private hosts for webhooks; local development only
 * @param limits              platform maximums no merchant configuration may exceed
 * @param defaults            configuration given to newly registered merchants
 */
@Validated
@ConfigurationProperties(prefix = "hpg.merchant")
public record MerchantProperties(
        @NotNull String currency,
        @Min(1) int maxActiveApiKeys,
        boolean allowInsecureWebhooks,
        @Valid @NotNull Limits limits,
        @Valid @NotNull Defaults defaults) {

    public record Limits(
            @NotNull BigDecimal minAmountFloor,
            @NotNull BigDecimal maxAmountPerTransaction,
            @NotNull BigDecimal maxDailyLimit,
            @Min(1) int minPaymentTtlMinutes,
            @Min(1) int maxPaymentTtlMinutes,
            @Min(1) int maxAttempts,
            @Min(1) int maxRefundWindowDays) {
    }

    public record Defaults(
            @NotEmpty Set<String> enabledMethods,
            @NotNull String captureMode,
            @NotNull BigDecimal minAmount,
            @NotNull BigDecimal maxAmountPerTransaction,
            @NotNull BigDecimal dailyLimit,
            @Min(1) int paymentTtlMinutes,
            @Min(1) int maxAttempts,
            @Min(1) int refundWindowDays,
            boolean notifyPatientEmail,
            boolean notifyPatientSms) {
    }
}
