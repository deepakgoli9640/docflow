package com.hpg.events.reconciliation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** {@code ReconciliationCompleted} on {@code reconciliation.events.v1} (key = businessDate). */
public record ReconciliationCompletedPayload(
        UUID batchId,
        LocalDate businessDate,
        String source,
        int matched,
        int mismatched,
        int missingInProvider,
        int missingInInternalSystem,
        int duplicate,
        int pendingReview,
        int malformedRows,
        Instant completedAt) {

    public static final String EVENT_TYPE = "ReconciliationCompleted";
    public static final int CURRENT_VERSION = 1;

    public int discrepancies() {
        return mismatched + missingInProvider + missingInInternalSystem + duplicate + pendingReview;
    }
}
