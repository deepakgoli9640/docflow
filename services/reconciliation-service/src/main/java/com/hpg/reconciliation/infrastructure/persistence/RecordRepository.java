package com.hpg.reconciliation.infrastructure.persistence;

import com.hpg.reconciliation.domain.ReconciliationRecord;
import com.hpg.reconciliation.domain.ResolutionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface RecordRepository extends JpaRepository<ReconciliationRecord, UUID>, JpaSpecificationExecutor<ReconciliationRecord> {

    List<ReconciliationRecord> findByBatchIdOrderByStatusAscProviderReferenceAsc(UUID batchId);

    long countByResolutionStatusIn(List<ResolutionStatus> statuses);
}
