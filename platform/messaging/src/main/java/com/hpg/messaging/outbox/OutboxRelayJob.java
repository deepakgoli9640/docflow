package com.hpg.messaging.outbox;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the relay on a schedule. {@link SchedulerLock} keeps it to one replica at a time, which is what
 * preserves event order when the service is scaled out.
 */
public class OutboxRelayJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayJob.class);

    private final OutboxRelay relay;

    public OutboxRelayJob(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${hpg.outbox.interval:PT1S}")
    @SchedulerLock(name = "outbox-relay", lockAtMostFor = "PT2M")
    public void publish() {
        // Drain quickly after a backlog (e.g. Kafka was down) instead of one batch per tick.
        for (int round = 0; round < 10; round++) {
            if (relay.publishBatch() < relay.properties().batchSize()) {
                return;
            }
        }
    }

    @Scheduled(cron = "${hpg.outbox.cleanup-cron:0 23 * * * *}")
    @SchedulerLock(name = "outbox-cleanup", lockAtMostFor = "PT10M")
    public void cleanUp() {
        int deleted = relay.deletePublishedOlderThan(relay.properties().retention());
        if (deleted > 0) {
            log.info("Deleted {} published outbox row(s) older than {}", deleted, relay.properties().retention());
        }
    }
}
