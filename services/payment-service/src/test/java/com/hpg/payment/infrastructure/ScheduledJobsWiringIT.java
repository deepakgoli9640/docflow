package com.hpg.payment.infrastructure;

import com.hpg.payment.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the scheduled jobs are actually registered with a scheduler.
 *
 * <p>This test exists because of a bug it would have caught: the shared scheduling auto-configuration
 * depended on a {@code DataSource} bean without declaring that it must be processed after the
 * DataSource auto-configuration. The condition was evaluated too early, the whole configuration was
 * skipped, and every {@code @Scheduled} method in the service silently never ran — while all other
 * tests passed, because they call the recovery and relay services directly and switch scheduling off.
 *
 * <p>Asserting on method names is deliberately blunt: the point is only that the scheduler knows about
 * them. What each job does is tested where that logic lives.
 */
@SpringBootTest(properties = {
        "hpg.payment.provider=simulated",
        "hpg.scheduling.enabled=true",
        "hpg.outbox.enabled=true",
        "hpg.payment.jobs.enabled=true",
        // No broker: a relay tick fails and logs, which is fine. Only the wiring is under test.
        "spring.kafka.bootstrap-servers=localhost:1"})
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class ScheduledJobsWiringIT {

    @Autowired
    List<ScheduledTaskHolder> taskHolders;

    @Test
    void everyRecoveryAndRelayJobIsRegisteredWithTheScheduler() {
        Set<String> scheduledMethods = taskHolders.stream()
                .flatMap(holder -> holder.getScheduledTasks().stream())
                .map(ScheduledJobsWiringIT::methodNameOf)
                .collect(Collectors.toSet());

        assertThat(scheduledMethods).contains(
                "publish",                  // outbox relay
                "cleanUp",                  // outbox cleanup
                "retryOrderCreation",       // payments whose provider order was never created
                "verifyInFlight",           // PROCESSING / UNKNOWN / long-PENDING payments
                "expire",                   // expiry, after asking the provider
                "cleanIdempotencyKeys");
    }

    /**
     * Spring wraps the method in its own runnable, so the method is read from the task's description
     * ("com.hpg...PaymentJobs.verifyInFlight") rather than by casting the runnable.
     */
    private static String methodNameOf(ScheduledTask task) {
        String description = task.getTask().toString();
        int lastDot = description.lastIndexOf('.');
        return lastDot < 0 ? description : description.substring(lastDot + 1);
    }
}
