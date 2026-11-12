package com.hpg.merchant.api.dto;

import com.hpg.common.money.Money;
import com.hpg.merchant.domain.CaptureMode;
import com.hpg.merchant.domain.MerchantConfiguration;
import com.hpg.merchant.domain.PaymentMethod;
import com.hpg.merchant.domain.PaymentSettings;

import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;

public record ConfigurationResponse(
        Set<PaymentMethod> enabledMethods,
        CaptureMode captureMode,
        Money minAmount,
        Money maxAmountPerTransaction,
        Money dailyLimit,
        int paymentTtlMinutes,
        int maxAttempts,
        int refundWindowDays,
        String webhookUrl,
        boolean webhookSecretConfigured,
        boolean notifyPatientEmail,
        boolean notifyPatientSms,
        Instant updatedAt) {

    public static ConfigurationResponse from(MerchantConfiguration c) {
        PaymentSettings s = c.settings();
        return new ConfigurationResponse(new TreeSet<>(s.enabledMethods()), s.captureMode(), s.minAmount(),
                s.maxAmountPerTransaction(), s.dailyLimit(), s.paymentTtlMinutes(), s.maxAttempts(),
                s.refundWindowDays(), s.webhookUrl(), c.getWebhookSecret() != null, s.notifyPatientEmail(),
                s.notifyPatientSms(), c.getUpdatedAt());
    }
}
