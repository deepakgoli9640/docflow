package com.hpg.merchant.api.dto;

import com.hpg.merchant.domain.CaptureMode;
import com.hpg.merchant.domain.PaymentMethod;
import com.hpg.merchant.domain.PaymentSettings;
import com.hpg.web.dto.MoneyRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;

/** Full configuration (PUT replaces everything). Platform maximums are checked in the service. */
public record ConfigurationRequest(
        @NotEmpty Set<PaymentMethod> enabledMethods,
        @NotNull CaptureMode captureMode,
        @NotNull @Valid MoneyRequest minAmount,
        @NotNull @Valid MoneyRequest maxAmountPerTransaction,
        @NotNull @Valid MoneyRequest dailyLimit,
        @Min(1) @Max(10_080) int paymentTtlMinutes,
        @Min(1) @Max(10) int maxAttempts,
        @Min(1) @Max(3650) int refundWindowDays,
        @Size(max = 2048) String webhookUrl,
        boolean notifyPatientEmail,
        boolean notifyPatientSms) {

    public PaymentSettings toSettings() {
        return new PaymentSettings(enabledMethods, captureMode, minAmount.toMoney(),
                maxAmountPerTransaction.toMoney(), dailyLimit.toMoney(), paymentTtlMinutes, maxAttempts,
                refundWindowDays, webhookUrl == null || webhookUrl.isBlank() ? null : webhookUrl.trim(),
                notifyPatientEmail, notifyPatientSms);
    }
}
