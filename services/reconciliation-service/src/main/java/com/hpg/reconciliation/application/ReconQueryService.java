package com.hpg.reconciliation.application;

import com.hpg.common.error.NotFoundException;
import com.hpg.reconciliation.domain.BatchStatus;
import com.hpg.reconciliation.domain.MatchStatus;
import com.hpg.reconciliation.domain.ReconErrorCode;
import com.hpg.reconciliation.domain.ReconciliationBatch;
import com.hpg.reconciliation.domain.ReconciliationRecord;
import com.hpg.reconciliation.domain.ResolutionStatus;
import com.hpg.reconciliation.infrastructure.persistence.BatchRepository;
import com.hpg.reconciliation.infrastructure.persistence.RecordRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ReconQueryService {

    private final BatchRepository batches;
    private final RecordRepository records;

    public ReconQueryService(BatchRepository batches, RecordRepository records) {
        this.batches = batches;
        this.records = records;
    }

    public record DailyReport(ReconciliationBatch batch, List<ReconciliationRecord> records) {
    }

    public Page<ReconciliationBatch> batches(LocalDate businessDate, BatchStatus status, Pageable pageable) {
        Specification<ReconciliationBatch> spec = (root, q, cb) -> cb.conjunction();
        if (businessDate != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("businessDate"), businessDate));
        }
        if (status != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        return batches.findAll(spec, pageable);
    }

    public ReconciliationBatch batch(UUID id) {
        return batches.findById(id).orElseThrow(() ->
                new NotFoundException(ReconErrorCode.BATCH_NOT_FOUND, "Reconciliation batch " + id + " not found"));
    }

    public Page<ReconciliationRecord> records(UUID batchId, MatchStatus status, ResolutionStatus resolutionStatus,
            Pageable pageable) {
        batch(batchId);
        Specification<ReconciliationRecord> spec = (root, q, cb) -> cb.equal(root.get("batchId"), batchId);
        if (status != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        if (resolutionStatus != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("resolutionStatus"), resolutionStatus));
        }
        return records.findAll(spec, pageable);
    }

    public ReconciliationRecord record(UUID id) {
        return records.findById(id).orElseThrow(() ->
                new NotFoundException(ReconErrorCode.RECORD_NOT_FOUND, "Reconciliation record " + id + " not found"));
    }

    /** The current view of a date: its newest completed batch and all of that batch's records. */
    public DailyReport daily(LocalDate date) {
        ReconciliationBatch b = batches.findFirstByBusinessDateAndStatusOrderByStartedAtDesc(date, BatchStatus.COMPLETED)
                .orElseThrow(() -> new NotFoundException(ReconErrorCode.NO_COMPLETED_BATCH,
                        "No completed reconciliation for " + date));
        return new DailyReport(b, records.findByBatchIdOrderByStatusAscProviderReferenceAsc(b.getId()));
    }
}
