package com.hpg.payment.infrastructure.scheduling;

import com.hpg.payment.application.PaymentRecoveryService;
import com.hpg.payment.infrastructure.persistence.IdempotencyStore;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/** Thin scheduling layer: timing and locking only; the logic lives in {@link PaymentRecoveryService}. */
@Component
@ConditionalOnProperty(name = "hpg.payment.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentJobs {

    private static final Logger log = LoggerFactory.getLogger(PaymentJobs.class);

    private final PaymentRecoveryService recovery;
    private final IdempotencyStore idempotency;
    private final Clock clock;

    public PaymentJobs(PaymentRecoveryService recovery, IdempotencyStore idempotency, Clock clock) {
        this.recovery = recovery;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${hpg.payment.jobs.interval:PT1M}", initialDelayString = "PT20S")
    @SchedulerLock(name = "payment-order-creation-retry", lockAtMostFor = "PT2M")
    public void retryOrderCreation() {
        log(recovery.retryOrderCreation(), "order creation retried");
    }

    @Scheduled(fixedDelayString = "${hpg.payment.jobs.interval:PT1M}", initialDelayString = "PT25S")
    @SchedulerLock(name = "payment-verification", lockAtMostFor = "PT4M")
    public void verifyInFlight() {
        log(recovery.verifyInFlight(), "payments verified with provider");
    }

    @Scheduled(fixedDelayString = "${hpg.payment.jobs.interval:PT1M}", initialDelayString = "PT30S")
    @SchedulerLock(name = "payment-expiry", lockAtMostFor = "PT4M")
    public void expire() {
        log(recovery.expirePayments(), "expired payments processed");
        log(recovery.expireAuthorizations(), "expired authorizations processed");
    }

    @Scheduled(cron = "0 17 * * * *")
    @SchedulerLock(name = "idempotency-key-cleanup", lockAtMostFor = "PT10M")
    public void cleanIdempotencyKeys() {
        log(idempotency.deleteExpired(clock.instant(), 5_000), "expired idempotency keys deleted");
    }

    private static void log(int count, String what) {
        if (count > 0) {
            log.info("{}: {}", what, count);
        }
    }
}
