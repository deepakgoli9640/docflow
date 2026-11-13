package com.hpg.reconciliation.application;

import com.hpg.common.error.NotFoundException;
import com.hpg.persistence.Actors;
import com.hpg.reconciliation.domain.ReconErrorCode;
import com.hpg.reconciliation.domain.ReconciliationRecord;
import com.hpg.reconciliation.domain.ResolutionType;
import com.hpg.reconciliation.infrastructure.persistence.RecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * The discrepancy workflow. It changes the reconciliation record only; a ledger correction is a separate,
 * deliberate action whose transaction reference is recorded with the resolution.
 */
@Service
public class DiscrepancyService {

    private static final Logger log = LoggerFactory.getLogger(DiscrepancyService.class);

    private final RecordRepository records;
    private final Clock clock;

    public DiscrepancyService(RecordRepository records, Clock clock) {
        this.records = records;
        this.clock = clock;
    }

    @Transactional
    public ReconciliationRecord startReview(UUID recordId) {
        ReconciliationRecord r = require(recordId);
        r.startReview();
        log.info("Discrepancy {} taken into review by {}", recordId, Actors.current());
        return records.save(r);
    }

    @Transactional
    public ReconciliationRecord resolve(UUID recordId, ResolutionType type, String note, String correctionReference) {
        ReconciliationRecord r = require(recordId);
        r.resolve(type, note, correctionReference, Actors.current(), clock.instant());
        log.info("Discrepancy {} ({}) resolved as {} by {}", recordId, r.getStatus(), type, Actors.current());
        return records.save(r);
    }

    private ReconciliationRecord require(UUID id) {
        return records.findById(id).orElseThrow(() ->
                new NotFoundException(ReconErrorCode.RECORD_NOT_FOUND, "Reconciliation record " + id + " not found"));
    }
}
