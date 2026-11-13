package com.hpg.reconciliation.api.dto;

import com.hpg.reconciliation.domain.ReconciliationBatch;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record BatchResponse(
        UUID id,
        LocalDate businessDate,
        String source,
        String provider,
        String fileName,
        String status,
        int providerRows,
        int malformedRows,
        int matched,
        int mismatched,
        int missingInProvider,
        int missingInInternalSystem,
        int duplicate,
        int pendingReview,
        int discrepancies,
        String failureReason,
        String startedBy,
        Instant startedAt,
        Instant completedAt) {

    public static BatchResponse from(ReconciliationBatch b) {
        return new BatchResponse(b.getId(), b.getBusinessDate(), b.getSource().name(), b.getProvider(), b.getFileName(),
                b.getStatus().name(), b.getProviderRowCount(), b.getMalformedRowCount(), b.getMatchedCount(),
                b.getMismatchedCount(), b.getMissingInProviderCount(), b.getMissingInternalCount(), b.getDuplicateCount(),
                b.getPendingReviewCount(), b.discrepancyCount(), b.getFailureReason(), b.getCreatedBy(), b.getStartedAt(),
                b.getCompletedAt());
    }
}
