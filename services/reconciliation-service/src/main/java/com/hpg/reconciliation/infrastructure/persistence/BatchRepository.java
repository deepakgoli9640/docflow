package com.hpg.reconciliation.infrastructure.persistence;

import com.hpg.reconciliation.domain.BatchStatus;
import com.hpg.reconciliation.domain.ReconciliationBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BatchRepository extends JpaRepository<ReconciliationBatch, UUID>, JpaSpecificationExecutor<ReconciliationBatch> {

    Optional<ReconciliationBatch> findFirstByBusinessDateAndStatusOrderByStartedAtDesc(LocalDate businessDate, BatchStatus status);

    boolean existsByFileSha256(String fileSha256);

    List<ReconciliationBatch> findByStatusAndStartedAtBefore(BatchStatus status, Instant cutoff);

    Optional<ReconciliationBatch> findFirstByStatusOrderByCompletedAtDesc(BatchStatus status);
}
