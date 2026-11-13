package com.hpg.events.merchant;

import com.hpg.common.money.Money;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Everything other services are allowed to know about a merchant's configuration (version 1).
 *
 * <p>The webhook <b>signing secret is deliberately absent</b>. Topics are retained and readable by every
 * consumer, and a secret that reaches a log or a replay would have to be rotated everywhere;
 * notification-service fetches it over an authenticated internal call instead
 * (docs/01-architecture-overview.md §4.2).
 *
 * @param version snapshot version from the owning service; a consumer ignores anything not newer than
 *                what it already has, so replays and redeliveries cannot move a replica backwards
 */
public record MerchantSnapshotPayload(
        UUID merchantId,
        long version,
        String legalName,
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
        String webhookUrl,
        boolean notifyPatientEmail,
        boolean notifyPatientSms,
        Instant updatedAt) {

    /** Whether this merchant may obtain tokens and use the API at all. */
    public boolean apiAccessAllowed() {
        return "ACTIVE".equals(status) || "PENDING_ACTIVATION".equals(status);
    }

    /** Whether this merchant may take payments. */
    public boolean canTakePayments() {
        return "ACTIVE".equals(status);
    }
}
