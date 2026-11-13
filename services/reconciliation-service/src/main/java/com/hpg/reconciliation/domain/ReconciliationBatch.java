package com.hpg.reconciliation.domain;

import com.hpg.common.id.Ids;
import com.hpg.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/** One reconciliation run for a business date: RUNNING, then COMPLETED with counts, or FAILED. */
@Entity
@Table(name = "reconciliation_batches")
public class ReconciliationBatch extends AuditableEntity {

    @Id
    private UUID id;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 10)
    private BatchSource source;

    @Column(name = "provider", nullable = false, length = 20)
    private String provider;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "file_sha256", length = 64)
    private String fileSha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private BatchStatus status;

    @Column(name = "provider_row_count", nullable = false)
    private int providerRowCount;

    @Column(name = "matched_count", nullable = false)
    private int matchedCount;

    @Column(name = "mismatched_count", nullable = false)
    private int mismatchedCount;

    @Column(name = "missing_in_provider_count", nullable = false)
    private int missingInProviderCount;

    @Column(name = "missing_internal_count", nullable = false)
    private int missingInternalCount;

    @Column(name = "duplicate_count", nullable = false)
    private int duplicateCount;

    @Column(name = "pending_review_count", nullable = false)
    private int pendingReviewCount;

    @Column(name = "malformed_row_count", nullable = false)
    private int malformedRowCount;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected ReconciliationBatch() {
        // for JPA
    }

    public ReconciliationBatch(LocalDate businessDate, BatchSource source, String provider, String fileName,
            String fileSha256, Instant now) {
        this.id = Ids.newId();
        this.businessDate = businessDate;
        this.source = source;
        this.provider = provider;
        this.fileName = fileName;
        this.fileSha256 = fileSha256;
        this.status = BatchStatus.RUNNING;
        this.startedAt = now;
    }

    public void complete(int providerRows, int malformedRows, Map<MatchStatus, Integer> counts, Instant now) {
        this.providerRowCount = providerRows;
        this.malformedRowCount = malformedRows;
        this.matchedCount = counts.getOrDefault(MatchStatus.MATCHED, 0);
        this.mismatchedCount = counts.getOrDefault(MatchStatus.MISMATCHED, 0);
        this.missingInProviderCount = counts.getOrDefault(MatchStatus.MISSING_IN_PROVIDER, 0);
        this.missingInternalCount = counts.getOrDefault(MatchStatus.MISSING_IN_INTERNAL_SYSTEM, 0);
        this.duplicateCount = counts.getOrDefault(MatchStatus.DUPLICATE, 0);
        this.pendingReviewCount = counts.getOrDefault(MatchStatus.PENDING_REVIEW, 0);
        this.status = BatchStatus.COMPLETED;
        this.completedAt = now;
    }

    public void fail(String reason, Instant now) {
        this.status = BatchStatus.FAILED;
        this.failureReason = reason == null || reason.length() <= 500 ? reason : reason.substring(0, 500);
        this.completedAt = now;
    }

    public int discrepancyCount() {
        return mismatchedCount + missingInProviderCount + missingInternalCount + duplicateCount + pendingReviewCount;
    }

    public UUID getId() {
        return id;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public BatchSource getSource() {
        return source;
    }

    public String getProvider() {
        return provider;
    }

    public String getFileName() {
        return fileName;
    }

    public BatchStatus getStatus() {
        return status;
    }

    public int getProviderRowCount() {
        return providerRowCount;
    }

    public int getMatchedCount() {
        return matchedCount;
    }

    public int getMismatchedCount() {
        return mismatchedCount;
    }

    public int getMissingInProviderCount() {
        return missingInProviderCount;
    }

    public int getMissingInternalCount() {
        return missingInternalCount;
    }

    public int getDuplicateCount() {
        return duplicateCount;
    }

    public int getPendingReviewCount() {
        return pendingReviewCount;
    }

    public int getMalformedRowCount() {
        return malformedRowCount;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
