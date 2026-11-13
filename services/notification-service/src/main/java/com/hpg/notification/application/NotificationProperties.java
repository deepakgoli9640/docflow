package com.hpg.notification.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Notification settings (prefix {@code hpg.notification}).
 *
 * @param fromAddress        envelope sender for patient email
 * @param dispatchBatchSize  notifications delivered per dispatcher run
 * @param maxAttempts        attempts before a notification is declared dead
 * @param retryInitial       first retry delay
 * @param retryMultiplier    growth factor between retries
 * @param retryMax           longest gap between retries
 * @param stuckSendingAfter  how long a claimed notification may stay SENDING before it is retried
 * @param webhookConnectTimeout how long to wait for a hospital endpoint to accept a connection
 * @param webhookReadTimeout how long to wait for its response
 * @param allowPrivateWebhookTargets local development only: allow http:// and private addresses
 * @param merchantConfigCacheTtl how long a fetched webhook secret is cached (bounds rotation lag)
 */
@ConfigurationProperties(prefix = "hpg.notification")
public record NotificationProperties(
        String fromAddress,
        Integer dispatchBatchSize,
        Integer maxAttempts,
        Duration retryInitial,
        Double retryMultiplier,
        Duration retryMax,
        Duration stuckSendingAfter,
        Duration webhookConnectTimeout,
        Duration webhookReadTimeout,
        boolean allowPrivateWebhookTargets,
        Duration merchantConfigCacheTtl) {

    public NotificationProperties {
        fromAddress = fromAddress == null ? "payments@hpg.example" : fromAddress;
        dispatchBatchSize = dispatchBatchSize == null || dispatchBatchSize <= 0 ? 25 : dispatchBatchSize;
        // 12 attempts with these settings spread over roughly a day, which is long enough for a hospital
        // to notice and fix a broken endpoint without us giving up on the first bad afternoon.
        maxAttempts = maxAttempts == null || maxAttempts <= 0 ? 12 : maxAttempts;
        retryInitial = retryInitial == null ? Duration.ofSeconds(30) : retryInitial;
        retryMultiplier = retryMultiplier == null ? 3.0 : retryMultiplier;
        retryMax = retryMax == null ? Duration.ofHours(2) : retryMax;
        stuckSendingAfter = stuckSendingAfter == null ? Duration.ofMinutes(5) : stuckSendingAfter;
        webhookConnectTimeout = webhookConnectTimeout == null ? Duration.ofSeconds(3) : webhookConnectTimeout;
        webhookReadTimeout = webhookReadTimeout == null ? Duration.ofSeconds(10) : webhookReadTimeout;
        merchantConfigCacheTtl = merchantConfigCacheTtl == null ? Duration.ofMinutes(5) : merchantConfigCacheTtl;
    }
}
