package com.hpg.reconciliation.domain;

import com.hpg.common.error.BusinessException;
import com.hpg.common.id.Ids;
import com.hpg.persistence.AuditableEntity;
import com.hpg.reconciliation.matching.InternalTransaction;
import com.hpg.reconciliation.matching.MatchOutcome;
import com.hpg.reconciliation.matching.SettlementRow;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The reconciliation result for one item. The match status is fixed when the batch completes; only the
 * resolution workflow of a discrepancy changes afterwards (OPEN → IN_REVIEW → RESOLVED). Resolving never
 * touches money records: a ledger correction is a separate action whose reference is recorded here.
 */
@Entity
@Table(name = "reconciliation_records")
public class ReconciliationRecord extends AuditableEntity {

    @Id
    private UUID id;

    @Column(name = "batch_id", nullable = false, updatable = false)
    private UUID batchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30, updatable = false)
    private MatchStatus status;

    @Column(name = "entity_type", length = 20, updatable = false)
    private String entityType;

    @Column(name = "provider_reference", length = 64, updatable = false)
    private String providerReference;

    @Column(name = "internal_transaction_ref", length = 30, updatable = false)
    private String internalTransactionRef;

    @Column(name = "merchant_id", updatable = false)
    private UUID merchantId;

    @Column(name = "internal_amount", precision = 19, scale = 2, updatable = false)
    private BigDecimal internalAmount;

    @Column(name = "provider_amount", precision = 19, scale = 2, updatable = false)
    private BigDecimal providerAmount;

    @Column(name = "currency", length = 3, updatable = false)
    private String currency;

    @Column(name = "settlement_record_id", updatable = false)
    private Long settlementRecordId;

    @Column(name = "detail", length = 500, updatable = false)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_status", length = 10)
    private ResolutionStatus resolutionStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_type", length = 30)
    private ResolutionType resolutionType;

    @Column(name = "resolution_note", length = 1000)
    private String resolutionNote;

    @Column(name = "correction_reference", length = 64)
    private String correctionReference;

    @Column(name = "resolved_by", length = 100)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected ReconciliationRecord() {
        // for JPA
    }

    public ReconciliationRecord(UUID batchId, MatchOutcome outcome, Long settlementRecordId) {
        this.id = Ids.newId();
        this.batchId = batchId;
        this.status = outcome.status();
        SettlementRow p = outcome.provider();
        InternalTransaction t = outcome.internal();
        this.entityType = p != null && p.entityType() != null ? p.entityType() : t != null ? t.providerEntityType() : null;
        this.providerReference = p != null && p.entityId() != null ? p.entityId() : t != null ? t.providerReference() : null;
        this.internalTransactionRef = t == null ? null : t.transactionRef();
        this.merchantId = t == null ? null : t.merchantId();
        this.internalAmount = t == null ? null : t.amount();
        this.providerAmount = p == null ? null : p.amount();
        this.currency = p != null && p.currency() != null ? p.currency() : t != null ? t.currency() : null;
        this.settlementRecordId = settlementRecordId;
        this.detail = outcome.detail() == null || outcome.detail().length() <= 500 ? outcome.detail()
                : outcome.detail().substring(0, 500);
        this.resolutionStatus = status == MatchStatus.MATCHED ? null : ResolutionStatus.OPEN;
    }

    public void startReview() {
        requireDiscrepancy();
        if (resolutionStatus != ResolutionStatus.OPEN) {
            throw new BusinessException(ReconErrorCode.INVALID_RESOLUTION_TRANSITION,
                    "Only OPEN discrepancies can be taken into review (this one is " + resolutionStatus + ").");
        }
        resolutionStatus = ResolutionStatus.IN_REVIEW;
    }

    public void resolve(ResolutionType type, String note, String correctionReference, String actor, Instant now) {
        requireDiscrepancy();
        if (resolutionStatus == ResolutionStatus.RESOLVED) {
            throw new BusinessException(ReconErrorCode.INVALID_RESOLUTION_TRANSITION, "Already resolved.");
        }
        if (type == ResolutionType.INTERNAL_CORRECTION_POSTED && (correctionReference == null || correctionReference.isBlank())) {
            throw new BusinessException(com.hpg.common.error.CommonErrorCode.VALIDATION_FAILED,
                    "INTERNAL_CORRECTION_POSTED needs the transaction reference of the correction.");
        }
        this.resolutionStatus = ResolutionStatus.RESOLVED;
        this.resolutionType = type;
        this.resolutionNote = note;
        this.correctionReference = correctionReference;
        this.resolvedBy = actor;
        this.resolvedAt = now;
    }

    private void requireDiscrepancy() {
        if (status == MatchStatus.MATCHED) {
            throw new BusinessException(ReconErrorCode.NOT_A_DISCREPANCY, "This record matched; there is nothing to resolve.");
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getBatchId() {
        return batchId;
    }

    public MatchStatus getStatus() {
        return status;
    }

    public String getEntityType() {
        return entityType;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public String getInternalTransactionRef() {
        return internalTransactionRef;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public BigDecimal getInternalAmount() {
        return internalAmount;
    }

    public BigDecimal getProviderAmount() {
        return providerAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getDetail() {
        return detail;
    }

    public ResolutionStatus getResolutionStatus() {
        return resolutionStatus;
    }

    public ResolutionType getResolutionType() {
        return resolutionType;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public String getCorrectionReference() {
        return correctionReference;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
