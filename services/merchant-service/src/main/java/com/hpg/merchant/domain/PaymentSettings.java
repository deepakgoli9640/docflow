package com.hpg.merchant.domain;

import com.hpg.common.money.Money;

import java.util.Set;

/** The editable part of a merchant's configuration, as one immutable value. */
public record PaymentSettings(
        Set<PaymentMethod> enabledMethods,
        CaptureMode captureMode,
        Money minAmount,
        Money maxAmountPerTransaction,
        Money dailyLimit,
        int paymentTtlMinutes,
        int maxAttempts,
        int refundWindowDays,
        String webhookUrl,
        boolean notifyPatientEmail,
        boolean notifyPatientSms) {

    public PaymentSettings {
        enabledMethods = Set.copyOf(enabledMethods);
    }

    public PaymentSettings withWebhookUrl(String url) {
        return new PaymentSettings(enabledMethods, captureMode, minAmount, maxAmountPerTransaction, dailyLimit,
                paymentTtlMinutes, maxAttempts, refundWindowDays, url, notifyPatientEmail, notifyPatientSms);
    }
}
