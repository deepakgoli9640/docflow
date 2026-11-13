package com.hpg.refund.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Refund states (docs/03-refund-lifecycle.md §5).
 *
 * <pre>
 * CREATED    --provider accepted (pending)-->  PROCESSING
 * CREATED    --provider processed at once-->   COMPLETED
 * CREATED    --provider rejected-->            FAILED      (reservation released)
 * CREATED    --provider call timed out-->      UNKNOWN
 * UNKNOWN    --lookup found it-->              PROCESSING / COMPLETED / FAILED
 * UNKNOWN    --lookup found nothing-->         CREATED     (safe to send again)
 * PROCESSING --webhook / poll-->               COMPLETED / FAILED
 * </pre>
 */
public enum RefundStatus {
    CREATED,
    PROCESSING,
    UNKNOWN,
    COMPLETED,
    FAILED;

    private static final Map<RefundStatus, Set<RefundStatus>> ALLOWED = Map.of(
            CREATED, EnumSet.of(PROCESSING, COMPLETED, FAILED, UNKNOWN),
            UNKNOWN, EnumSet.of(CREATED, PROCESSING, COMPLETED, FAILED),
            PROCESSING, EnumSet.of(COMPLETED, FAILED),
            COMPLETED, EnumSet.noneOf(RefundStatus.class),
            FAILED, EnumSet.noneOf(RefundStatus.class));

    public boolean canTransitionTo(RefundStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
