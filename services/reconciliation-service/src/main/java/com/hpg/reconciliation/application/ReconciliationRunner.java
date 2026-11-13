package com.hpg.reconciliation.application;

import com.hpg.common.error.BusinessException;
import com.hpg.events.Topics;
import com.hpg.events.reconciliation.ReconciliationCompletedPayload;
import com.hpg.messaging.outbox.OutboxWriter;
import com.hpg.reconciliation.domain.BatchSource;
import com.hpg.reconciliation.domain.BatchStatus;
import com.hpg.reconciliation.domain.MatchStatus;
import com.hpg.reconciliation.domain.ReconErrorCode;
import com.hpg.reconciliation.domain.ReconciliationBatch;
import com.hpg.reconciliation.domain.ReconciliationRecord;
import com.hpg.reconciliation.infrastructure.persistence.BatchRepository;
import com.hpg.reconciliation.infrastructure.persistence.InternalTransactionStore;
import com.hpg.reconciliation.infrastructure.persistence.RecordRepository;
import com.hpg.reconciliation.infrastructure.persistence.SettlementRecordStore;
import com.hpg.reconciliation.matching.MatchOutcome;
import com.hpg.reconciliation.matching.MatchingEngine;
import com.hpg.reconciliation.matching.SettlementCsvParser;
import com.hpg.reconciliation.matching.SettlementRow;
import com.hpg.reconciliation.source.SettlementReportSource;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Runs reconciliation batches (docs/05-reconciliation.md §6). Starting a batch is quick (the file is checked
 * and its rows stored; an API run only records the batch); matching happens in the background, which is
 * why the API answers 202 and clients poll the batch.
 */
@Service
public class ReconciliationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationRunner.class);
    private static final int INSERT_CHUNK = 1000;

    private final BatchRepository batches;
    private final RecordRepository records;
    private final SettlementRecordStore settlementRows;
    private final InternalTransactionStore internal;
    private final ObjectProvider<SettlementReportSource> apiSource;
    private final OutboxWriter outbox;
    private final ReconProperties properties;
    private final Executor executor;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;
    private final Clock clock;
    private final MatchingEngine engine = new MatchingEngine();

    public ReconciliationRunner(BatchRepository batches, RecordRepository records, SettlementRecordStore settlementRows,
            InternalTransactionStore internal, ObjectProvider<SettlementReportSource> apiSource, OutboxWriter outbox,
            ReconProperties properties, @org.springframework.beans.factory.annotation.Qualifier("reconExecutor") Executor executor,
            PlatformTransactionManager txManager, MeterRegistry meters, Clock clock) {
        this.batches = batches;
        this.records = records;
        this.settlementRows = settlementRows;
        this.internal = internal;
        this.apiSource = apiSource;
        this.outbox = outbox;
        this.properties = properties;
        this.executor = executor;
        this.tx = new TransactionTemplate(txManager);
        this.meters = meters;
        this.clock = clock;
    }

    /**
     * Loads a settlement CSV. The file is read twice from the upload: once for its SHA-256 (the same file must
     * not be loaded twice), once to stream its rows into the batch. Neither pass holds the file in memory.
     */
    public ReconciliationBatch startFromFile(LocalDate businessDate, String fileName, Supplier<InputStream> file) {
        requireValidDate(businessDate);
        String sha256 = sha256(file);
        if (batches.existsByFileSha256(sha256)) {
            throw duplicateFile();
        }
        SettlementCsvParser parser = new SettlementCsvParser(properties.maxFileRows());
        ReconciliationBatch batch;
        try {
            batch = tx.execute(s -> {
                ReconciliationBatch b = batches.saveAndFlush(new ReconciliationBatch(businessDate, BatchSource.FILE,
                        properties.provider().toUpperCase(java.util.Locale.ROOT), fileName, sha256, clock.instant()));
                List<SettlementRow> chunk = new ArrayList<>(INSERT_CHUNK);
                try (InputStream in = file.get()) {
                    parser.parse(in, row -> {
                        chunk.add(row);
                        if (chunk.size() == INSERT_CHUNK) {
                            settlementRows.insert(b.getId(), chunk);
                            chunk.clear();
                        }
                    });
                } catch (IOException e) {
                    throw new BusinessException(ReconErrorCode.INVALID_SETTLEMENT_FILE, "The file could not be read: " + e.getMessage());
                } catch (SettlementCsvParser.InvalidFileException e) {
                    throw new BusinessException(ReconErrorCode.INVALID_SETTLEMENT_FILE, e.getMessage());
                }
                if (!chunk.isEmpty()) {
                    settlementRows.insert(b.getId(), chunk);
                }
                return b;
            });
        } catch (DataIntegrityViolationException e) {
            throw duplicateFile(); // the same file uploaded twice at the same moment
        }
        log.info("Settlement file {} loaded into batch {} for {}", fileName, batch.getId(), businessDate);
        executor.execute(() -> complete(batch.getId()));
        return batch;
    }

    /** Fetches the provider's report over its API (Razorpay) and reconciles it. */
    public ReconciliationBatch startFromApi(LocalDate businessDate) {
        requireValidDate(businessDate);
        SettlementReportSource source = apiSource.getIfAvailable();
        if (source == null) {
            throw new BusinessException(ReconErrorCode.SOURCE_UNAVAILABLE, "This deployment has no provider API credentials ("
                    + properties.provider() + "). Upload the settlement report as a CSV file instead.");
        }
        ReconciliationBatch batch = tx.execute(s -> batches.save(new ReconciliationBatch(businessDate, BatchSource.API,
                source.provider(), null, null, clock.instant())));
        executor.execute(() -> {
            try {
                List<SettlementRow> rows = source.fetch(businessDate);
                tx.executeWithoutResult(s -> {
                    for (int i = 0; i < rows.size(); i += INSERT_CHUNK) {
                        settlementRows.insert(batch.getId(), rows.subList(i, Math.min(rows.size(), i + INSERT_CHUNK)));
                    }
                });
                complete(batch.getId());
            } catch (RuntimeException e) {
                fail(batch.getId(), e);
            }
        });
        return batch;
    }

    /** Matches a batch's provider rows against the ledger and stores the results. */
    void complete(UUID batchId) {
        long started = System.nanoTime();
        try {
            ReconciliationBatch done = tx.execute(s -> {
                ReconciliationBatch batch = batches.findById(batchId).orElseThrow();
                List<SettlementRecordStore.StoredRow> stored = settlementRows.load(batchId);
                List<SettlementRow> rows = stored.stream().map(SettlementRecordStore.StoredRow::row).toList();
                Map<SettlementRow, Long> rowIds = new IdentityHashMap<>();
                stored.forEach(r -> rowIds.put(r.row(), r.id()));
                List<String> refs = rows.stream().filter(SettlementRow::isValid).map(SettlementRow::entityId).toList();

                LocalDate date = batch.getBusinessDate();
                Instant dueBefore = date.minusDays(properties.settlementWindowDays()).plusDays(1)
                        .atStartOfDay(properties.businessZone()).toInstant();
                Instant lookbackFrom = date.minusDays(properties.lookbackDays()).atStartOfDay(properties.businessZone()).toInstant();
                List<MatchOutcome> outcomes = engine.match(new MatchingEngine.Input(rows, internal.byProviderReference(refs),
                        internal.dueUnsettled(dueBefore, lookbackFrom), internal.settledInOtherDays(refs, date)));

                Map<MatchStatus, Integer> counts = new EnumMap<>(MatchStatus.class);
                List<ReconciliationRecord> results = new ArrayList<>(outcomes.size());
                for (MatchOutcome o : outcomes) {
                    counts.merge(o.status(), 1, Integer::sum);
                    results.add(new ReconciliationRecord(batchId, o, o.provider() == null ? null : rowIds.get(o.provider())));
                }
                records.saveAll(results);
                int malformed = (int) rows.stream().filter(r -> !r.isValid()).count();
                batch.complete(rows.size(), malformed, counts, clock.instant());
                publishCompleted(batch);
                counts.forEach((status, n) -> meters.counter("recon.records", "status", status.name()).increment(n));
                return batches.save(batch);
            });
            meters.timer("recon.batch.duration").record(java.time.Duration.ofNanos(System.nanoTime() - started));
            log.info("Reconciliation batch {} for {} completed: {} provider rows, {} discrepancies", batchId,
                    done.getBusinessDate(), done.getProviderRowCount(), done.discrepancyCount());
        } catch (RuntimeException e) {
            fail(batchId, e);
        }
    }

    void fail(UUID batchId, Exception cause) {
        log.error("Reconciliation batch {} failed", batchId, cause);
        tx.executeWithoutResult(s -> batches.findById(batchId).filter(b -> b.getStatus() == BatchStatus.RUNNING)
                .ifPresent(b -> {
                    b.fail(cause.getMessage(), clock.instant());
                    batches.save(b);
                }));
    }

    private void publishCompleted(ReconciliationBatch b) {
        ReconciliationCompletedPayload payload = new ReconciliationCompletedPayload(b.getId(), b.getBusinessDate(),
                b.getSource().name(), b.getMatchedCount(), b.getMismatchedCount(), b.getMissingInProviderCount(),
                b.getMissingInternalCount(), b.getDuplicateCount(), b.getPendingReviewCount(), b.getMalformedRowCount(),
                b.getCompletedAt());
        outbox.append(Topics.RECONCILIATION_EVENTS, b.getBusinessDate().toString(), "ReconciliationBatch",
                b.getId().toString(), 1, null, ReconciliationCompletedPayload.EVENT_TYPE,
                ReconciliationCompletedPayload.CURRENT_VERSION, null, payload);
    }

    private void requireValidDate(LocalDate date) {
        LocalDate today = LocalDate.now(clock.withZone(properties.businessZone()));
        if (date == null || date.isAfter(today) || date.isBefore(today.minusYears(2))) {
            throw new BusinessException(ReconErrorCode.INVALID_BUSINESS_DATE,
                    "businessDate must be a past or current date (YYYY-MM-DD) within the last 2 years.");
        }
    }

    private static BusinessException duplicateFile() {
        return new BusinessException(ReconErrorCode.DUPLICATE_SETTLEMENT_FILE,
                "This exact file was already loaded. To reconcile the date again, use a corrected report.");
    }

    private static String sha256(Supplier<InputStream> file) {
        try (DigestInputStream in = new DigestInputStream(file.get(), MessageDigest.getInstance("SHA-256"))) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(in.getMessageDigest().digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new BusinessException(ReconErrorCode.INVALID_SETTLEMENT_FILE, "The file could not be read.");
        }
    }
}
