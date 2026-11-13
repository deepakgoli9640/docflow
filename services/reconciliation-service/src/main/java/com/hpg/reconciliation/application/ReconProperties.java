package com.hpg.reconciliation.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.time.ZoneId;

/**
 * @param provider             {@code razorpay}: API runs available; anything else: CSV uploads only
 * @param businessZone         business dates are calendar days in this zone
 * @param settlementWindowDays an internal transaction is flagged missing at the provider only once it is this
 *                             many days older than the business date (T+N settlement)
 * @param lookbackDays         how far back unsettled internal transactions are considered
 * @param staleRunAfter        a RUNNING batch older than this is marked FAILED (the service died mid-run)
 */
@ConfigurationProperties("hpg.reconciliation")
public record ReconProperties(
        @DefaultValue("razorpay") String provider,
        @DefaultValue("Asia/Kolkata") ZoneId businessZone,
        @DefaultValue("3") int settlementWindowDays,
        @DefaultValue("60") int lookbackDays,
        @DefaultValue("200000") int maxFileRows,
        @DefaultValue("30m") Duration staleRunAfter,
        ScheduledRun scheduledRun,
        Razorpay razorpay) {

    public record ScheduledRun(@DefaultValue("true") boolean enabled, @DefaultValue("0 0 6 * * *") String cron) {
    }

    public record Razorpay(String baseUrl, String keyId, String keySecret, @DefaultValue("2s") Duration connectTimeout,
            @DefaultValue("15s") Duration readTimeout) {

        @Override
        public String toString() {
            return "Razorpay[baseUrl=" + baseUrl + ", keyId=" + keyId + ", keySecret=****]";
        }
    }

    public boolean apiSourceAvailable() {
        return "razorpay".equalsIgnoreCase(provider);
    }
}
