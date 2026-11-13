package com.hpg.reconciliation.infrastructure.scheduling;

import com.hpg.reconciliation.application.ReconProperties;
import com.hpg.reconciliation.application.ReconciliationRunner;
import com.hpg.reconciliation.domain.BatchStatus;
import com.hpg.reconciliation.infrastructure.persistence.BatchRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;

@Component
public class ReconJobs {

    private static final Logger log = LoggerFactory.getLogger(ReconJobs.class);

    private final ReconciliationRunner runner;
    private final BatchRepository batches;
    private final ReconProperties properties;
    private final Clock clock;

    public ReconJobs(ReconciliationRunner runner, BatchRepository batches, ReconProperties properties, Clock clock) {
        this.runner = runner;
        this.batches = batches;
        this.properties = properties;
        this.clock = clock;
    }

    /** Daily API run for yesterday (business zone). Only where the provider API is configured. */
    @Scheduled(cron = "${hpg.reconciliation.scheduled-run.cron:0 0 6 * * *}", zone = "${hpg.reconciliation.business-zone:Asia/Kolkata}")
    @SchedulerLock(name = "recon-daily-run")
    public void dailyRun() {
        if (!properties.scheduledRun().enabled() || !properties.apiSourceAvailable()) {
            return;
        }
        LocalDate yesterday = LocalDate.now(clock.withZone(properties.businessZone())).minusDays(1);
        log.info("Scheduled reconciliation for {}", yesterday);
        runner.startFromApi(yesterday);
    }

    /** A batch still RUNNING long after it started belongs to an instance that died: mark it FAILED. */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    @SchedulerLock(name = "recon-stale-runs")
    @Transactional
    public void failStaleRuns() {
        batches.findByStatusAndStartedAtBefore(BatchStatus.RUNNING, clock.instant().minus(properties.staleRunAfter()))
                .forEach(b -> {
                    log.warn("Batch {} for {} was interrupted; marking FAILED. Start a new run for the date.", b.getId(),
                            b.getBusinessDate());
                    b.fail("Interrupted (the service stopped during the run). Start a new run for this date.", clock.instant());
                });
    }
}
