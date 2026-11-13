package com.hpg.notification.infrastructure.scheduling;

import com.hpg.messaging.consumer.ProcessedEvents;
import com.hpg.notification.application.NotificationDispatcher;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

@Component
@ConditionalOnProperty(name = "hpg.notification.dispatch.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationJobs {

    private final NotificationDispatcher dispatcher;
    private final ProcessedEvents processedEvents;
    private final Clock clock;

    public NotificationJobs(NotificationDispatcher dispatcher, ProcessedEvents processedEvents, Clock clock) {
        this.dispatcher = dispatcher;
        this.processedEvents = processedEvents;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${hpg.notification.dispatch.interval:1s}", initialDelayString = "PT5S")
    public void dispatch() {
        while (dispatcher.dispatchDue() > 0) {
            // keep going while there is a backlog
        }
    }

    @Scheduled(cron = "0 23 * * * *")
    @SchedulerLock(name = "notification-housekeeping")
    public void housekeeping() {
        processedEvents.purgeOlderThan(clock.instant().minus(Duration.ofDays(14)));
    }
}
