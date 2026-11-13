package com.hpg.events.merchant;

import com.hpg.common.money.Money;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Full current state of a merchant on {@code merchant.snapshots.v1} (log-compacted, key = merchantId).
 * Every change publishes the whole snapshot, so a consumer never needs older messages: reading the topic
 * from the beginning rebuilds any replica. Never contains secrets (API keys, webhook secret) or contact PII.
 *
 * @param snapshotVersion increases with every snapshot of this merchant; consumers ignore older versions
 */
public record MerchantSnapshotPayload(
        UUID merchantId,
        long snapshotVersion,
        String displayName,
        String status,
        Set<String> enabledMethods,
        String captureMode,
        Money minAmount,
        Money maxAmountPerTransaction,
        Money dailyLimit,
        int paymentTtlMinutes,
        int maxAttempts,
        int refundWindowDays,
        boolean webhookConfigured,
        boolean notifyPatientEmail,
        Instant occurredAt) {

    public static final String EVENT_TYPE = "MerchantSnapshot";
    public static final int CURRENT_VERSION = 1;
}
