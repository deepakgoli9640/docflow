package com.hpg.console.client.view;

import java.time.Instant;
import java.util.List;

public record ConfigurationView(List<String> enabledMethods, String captureMode, MoneyView minAmount,
        MoneyView maxAmountPerTransaction, MoneyView dailyLimit, int paymentTtlMinutes, int maxAttempts,
        int refundWindowDays, String webhookUrl, boolean webhookSecretConfigured, boolean notifyPatientEmail,
        boolean notifyPatientSms, Instant updatedAt) {

    public boolean accepts(String method) {
        return enabledMethods != null && enabledMethods.contains(method);
    }
}
