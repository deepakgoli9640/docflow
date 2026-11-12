package com.hpg.payment.provider.razorpay;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Razorpay settings (prefix {@code hpg.payment.razorpay}). Credentials come only from environment
 * variables: RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET, RAZORPAY_WEBHOOK_SECRET. Use TEST-mode keys
 * ({@code rzp_test_...}) for the sandbox.
 */
@ConfigurationProperties(prefix = "hpg.payment.razorpay")
public record RazorpayProperties(
        String baseUrl,
        String keyId,
        String keySecret,
        String webhookSecret,
        String checkoutScriptUrl,
        Duration connectTimeout,
        Duration readTimeout) {

    public RazorpayProperties {
        baseUrl = baseUrl == null ? "https://api.razorpay.com" : baseUrl;
        checkoutScriptUrl = checkoutScriptUrl == null ? "https://checkout.razorpay.com/v1/checkout.js" : checkoutScriptUrl;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
    }

    public void requireCredentials() {
        if (isBlank(keyId) || isBlank(keySecret) || isBlank(webhookSecret)) {
            throw new IllegalStateException("Razorpay provider selected but RAZORPAY_KEY_ID / RAZORPAY_KEY_SECRET / "
                    + "RAZORPAY_WEBHOOK_SECRET are not all set. Use test-mode keys, or set "
                    + "HPG_PAYMENT_PROVIDER=simulated for local development without Razorpay.");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @Override
    public String toString() {
        return "RazorpayProperties[baseUrl=" + baseUrl + ", keyId=" + keyId + ", secrets=****]";
    }
}
