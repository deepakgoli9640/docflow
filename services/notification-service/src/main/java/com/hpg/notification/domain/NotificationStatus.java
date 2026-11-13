package com.hpg.notification.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * <pre>
 * PENDING --claim--> SENDING --ok--> DELIVERED
 *                      |  \--retryable--> RETRYING --(due)--> SENDING ...
 *                      \--permanent, or retries exhausted--> DEAD
 * SKIPPED: nothing to deliver (the hospital configured no webhook URL, the patient left no email)
 * </pre>
 */
public enum NotificationStatus {
    PENDING,
    SENDING,
    DELIVERED,
    RETRYING,
    DEAD,
    SKIPPED;

    /** Statuses the dispatcher picks up when their time has come. */
    public static final Set<NotificationStatus> DUE_CANDIDATES = EnumSet.of(PENDING, RETRYING);

    public boolean isTerminal() {
        return this == DELIVERED || this == DEAD || this == SKIPPED;
    }
}
