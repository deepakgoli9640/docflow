package com.hpg.refund.provider.razorpay;

import java.time.Duration;

/** Razorpay settings under {@code hpg.refund.razorpay}. */
public record RazorpayProperties(String baseUrl, String keyId, String keySecret, String webhookSecret,
        Duration connectTimeout, Duration readTimeout) {

    public void requireCredentials() {
        if (isBlank(keyId) || isBlank(keySecret) || isBlank(webhookSecret)) {
            throw new IllegalStateException("Razorpay is the configured refund provider but RAZORPAY_KEY_ID, "
                    + "RAZORPAY_KEY_SECRET or RAZORPAY_WEBHOOK_SECRET is missing. Set TEST-mode keys, or use "
                    + "HPG_PAYMENT_PROVIDER=simulated for local demos.");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @Override
    public String toString() {
        return "RazorpayProperties[baseUrl=" + baseUrl + ", keyId=" + keyId + ", keySecret=****, webhookSecret=****]";
    }
}
