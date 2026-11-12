package com.hpg.payment.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.time.ZoneId;

/**
 * Payment settings (prefix {@code hpg.payment}).
 *
 * @param provider                 "razorpay" (default) or "simulated" (local demos only)
 * @param checkoutBaseUrl          public URL prefix of the hosted checkout, e.g. https://pay.example/checkout
 * @param allowInsecureReturnUrls  allow http:// return URLs (local development only)
 * @param captureWindow            how long an authorization stays capturable (provider auto-reverses after; verify)
 * @param idempotencyTtl           how long Idempotency-Key records are kept
 * @param webhookMaxAge            webhooks older than this are acknowledged but not applied (replay protection)
 * @param businessZone             calendar used for "daily" limits
 * @param jobs                     recovery job settings
 */
@ConfigurationProperties(prefix = "hpg.payment")
public record PaymentProperties(
        String provider,
        String checkoutBaseUrl,
        boolean allowInsecureReturnUrls,
        Duration captureWindow,
        Duration idempotencyTtl,
        Duration webhookMaxAge,
        ZoneId businessZone,
        Jobs jobs) {

    public PaymentProperties {
        provider = provider == null ? "razorpay" : provider;
        checkoutBaseUrl = checkoutBaseUrl == null ? "http://localhost:8080/checkout" : checkoutBaseUrl;
        captureWindow = captureWindow == null ? Duration.ofDays(5) : captureWindow;
        idempotencyTtl = idempotencyTtl == null ? Duration.ofHours(24) : idempotencyTtl;
        webhookMaxAge = webhookMaxAge == null ? Duration.ofHours(48) : webhookMaxAge;
        businessZone = businessZone == null ? ZoneId.of("Asia/Kolkata") : businessZone;
        jobs = jobs == null ? new Jobs(true, 50, null, null, null, null) : jobs;
    }

    /**
     * @param enabled               run the scheduled recovery jobs (disabled in tests, which call them directly)
     * @param batchSize             payments per job run
     * @param createdRetryAfter     retry provider order creation for CREATED payments older than this
     * @param processingVerifyAfter verify PROCESSING payments older than this
     * @param unknownVerifyAfter    verify UNKNOWN payments older than this
     * @param pendingVerifyInterval poll PENDING payments at most this often (catches lost callback + webhook)
     */
    public record Jobs(boolean enabled, int batchSize, Duration createdRetryAfter, Duration processingVerifyAfter,
            Duration unknownVerifyAfter, Duration pendingVerifyInterval) {

        public Jobs {
            batchSize = batchSize <= 0 ? 50 : batchSize;
            createdRetryAfter = createdRetryAfter == null ? Duration.ofSeconds(30) : createdRetryAfter;
            processingVerifyAfter = processingVerifyAfter == null ? Duration.ofMinutes(2) : processingVerifyAfter;
            unknownVerifyAfter = unknownVerifyAfter == null ? Duration.ofSeconds(30) : unknownVerifyAfter;
            pendingVerifyInterval = pendingVerifyInterval == null ? Duration.ofMinutes(10) : pendingVerifyInterval;
        }
    }
}
