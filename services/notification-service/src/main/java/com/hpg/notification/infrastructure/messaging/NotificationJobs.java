package com.hpg.notification.infrastructure.messaging;

import com.hpg.messaging.consumer.ConsumerProperties;
import com.hpg.messaging.consumer.ProcessedEvents;
import com.hpg.notification.application.NotificationDispatcher;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * The clock behind delivery. Short interval because the first attempt of a new notification should feel
 * immediate; the retry schedule itself lives in the {@code next_attempt_at} column, not here.
 */
@Component
@ConditionalOnProperty(name = "hpg.notification.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationJobs {

    private static final Logger log = LoggerFactory.getLogger(NotificationJobs.class);

    private final NotificationDispatcher dispatcher;
    private final ProcessedEvents processedEvents;
    private final ConsumerProperties consumerProperties;
    private final Clock clock;

    public NotificationJobs(NotificationDispatcher dispatcher, ProcessedEvents processedEvents,
            ConsumerProperties consumerProperties, Clock clock) {
        this.dispatcher = dispatcher;
        this.processedEvents = processedEvents;
        this.consumerProperties = consumerProperties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${hpg.notification.jobs.interval:PT2S}", initialDelayString = "PT5S")
    @SchedulerLock(name = "notification-dispatch", lockAtMostFor = "PT5M")
    public void dispatchDue() {
        int attempted = dispatcher.dispatchDue();
        if (attempted > 0) {
            log.debug("Attempted delivery of {} notification(s)", attempted);
        }
    }

    @Scheduled(fixedDelayString = "${hpg.notification.jobs.stuck-interval:PT1M}", initialDelayString = "PT30S")
    @SchedulerLock(name = "notification-stuck-recovery", lockAtMostFor = "PT5M")
    public void recoverStuck() {
        dispatcher.recoverStuck();
    }

    @Scheduled(cron = "${hpg.notification.jobs.cleanup-cron:0 41 3 * * *}")
    @SchedulerLock(name = "processed-events-cleanup", lockAtMostFor = "PT10M")
    public void cleanProcessedEvents() {
        int deleted = processedEvents.deleteOlderThan(
                clock.instant().minus(consumerProperties.processedRetention()), 10_000);
        if (deleted > 0) {
            log.info("Deleted {} processed-event row(s) past their retention", deleted);
        }
    }
}
