package com.hpg.refund.infrastructure.scheduling;

import com.hpg.messaging.consumer.ProcessedEvents;
import com.hpg.refund.application.RefundRecoveryService;
import com.hpg.refund.infrastructure.persistence.IdempotencyStore;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

@Component
@ConditionalOnProperty(name = "hpg.refund.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class RefundJobs {

    private final RefundRecoveryService recovery;
    private final IdempotencyStore idempotency;
    private final ProcessedEvents processedEvents;
    private final Clock clock;

    public RefundJobs(RefundRecoveryService recovery, IdempotencyStore idempotency, ProcessedEvents processedEvents,
            Clock clock) {
        this.recovery = recovery;
        this.idempotency = idempotency;
        this.processedEvents = processedEvents;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${hpg.refund.jobs.interval:PT30S}", initialDelayString = "PT20S")
    @SchedulerLock(name = "refund-recovery")
    public void recover() {
        recovery.recover();
    }

    @Scheduled(cron = "0 17 * * * *")
    @SchedulerLock(name = "refund-housekeeping")
    public void housekeeping() {
        idempotency.deleteExpired(clock.instant());
        // Payment events are retained 7 days; keep markers comfortably longer.
        processedEvents.purgeOlderThan(clock.instant().minus(Duration.ofDays(14)));
    }
}
