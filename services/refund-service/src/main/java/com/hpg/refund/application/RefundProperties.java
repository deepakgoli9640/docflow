package com.hpg.refund.application;

import com.hpg.refund.provider.razorpay.RazorpayProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param provider       {@code razorpay} or {@code simulated}
 * @param refundWindow   how long after capture a refund is accepted, unless the merchant snapshot sets its own
 * @param idempotencyTtl how long Idempotency-Key records are kept
 */
@ConfigurationProperties("hpg.refund")
public record RefundProperties(
        @DefaultValue("razorpay") String provider,
        @DefaultValue("180d") Duration refundWindow,
        @DefaultValue("24h") Duration idempotencyTtl,
        Jobs jobs,
        RazorpayProperties razorpay) {

    /**
     * @param createdRetryAfter     a CREATED refund whose last provider attempt is older than this is retried
     *                              (after a lookup)
     * @param unknownVerifyAfter    wait before looking up an UNKNOWN refund at the provider
     * @param processingVerifyAfter poll a PROCESSING refund if no webhook arrived within this time
     * @param maxProviderAttempts   give up (FAILED, reservation released) after this many attempts
     */
    public record Jobs(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("PT30S") Duration interval,
            @DefaultValue("50") int batchSize,
            @DefaultValue("30s") Duration createdRetryAfter,
            @DefaultValue("30s") Duration unknownVerifyAfter,
            @DefaultValue("2m") Duration processingVerifyAfter,
            @DefaultValue("5") int maxProviderAttempts) {
    }
}
