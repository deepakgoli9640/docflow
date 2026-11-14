package com.hpg.notification.application;

import com.hpg.events.Topics;
import com.hpg.events.notification.NotificationEventPayload;
import com.hpg.messaging.outbox.OutboxWriter;
import com.hpg.notification.delivery.DeliveryResult;
import com.hpg.notification.delivery.EmailSender;
import com.hpg.notification.delivery.WebhookSender;
import com.hpg.notification.domain.Channel;
import com.hpg.notification.domain.Notification;
import com.hpg.notification.infrastructure.persistence.NotificationRepository;
import com.hpg.notification.infrastructure.persistence.NotificationStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Delivers due notifications. Claim (short transaction) → deliver (no transaction: a slow hospital endpoint
 * must not hold database connections) → record the outcome (short transaction). Failures are retried with
 * exponential backoff + jitter until the attempt or age budget runs out; then the notification is DEAD and
 * {@code NotificationFailed} is published.
 */
@Service
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationStore store;
    private final NotificationRepository notifications;
    private final WebhookSender webhooks;
    private final EmailSender emails;
    private final com.hpg.notification.delivery.SmsSender sms;
    private final OutboxWriter outbox;
    private final Backoff backoff;
    private final NotificationProperties.Dispatch config;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;
    private final Clock clock;

    public NotificationDispatcher(NotificationStore store, NotificationRepository notifications, WebhookSender webhooks,
            EmailSender emails, OutboxWriter outbox, Backoff backoff, NotificationProperties properties,
            PlatformTransactionManager txManager, MeterRegistry meters, Clock clock,
            com.hpg.notification.delivery.SmsSender sms) {
        this.sms = sms;
        this.store = store;
        this.notifications = notifications;
        this.webhooks = webhooks;
        this.emails = emails;
        this.outbox = outbox;
        this.backoff = backoff;
        this.config = properties.dispatch();
        this.tx = new TransactionTemplate(txManager);
        this.meters = meters;
        this.clock = clock;
    }

    /** One dispatch round. @return notifications attempted */
    public int dispatchDue() {
        Instant now = clock.instant();
        List<UUID> claimed = store.claimDue(now, now.plus(config.lease()), config.batchSize());
        for (UUID id : claimed) {
            try {
                deliver(id);
            } catch (RuntimeException e) {
                // The lease expires and the notification is retried; never let one bad row stop the batch.
                log.error("Unexpected error delivering notification {}", id, e);
            }
        }
        return claimed.size();
    }

    private void deliver(UUID id) {
        Notification n = notifications.findById(id).orElseThrow();
        long started = System.nanoTime();
        DeliveryResult result = switch (n.getChannel()) {
            case WEBHOOK -> webhooks.send(n);
            case EMAIL -> emails.send(n);
            case SMS -> sms.send(n);
        };
        long durationMs = (System.nanoTime() - started) / 1_000_000;
        tx.executeWithoutResult(s -> record(id, result, durationMs));
    }

    private void record(UUID id, DeliveryResult result, long durationMs) {
        Notification n = notifications.findById(id).orElseThrow();
        Instant now = clock.instant();
        int attemptNo = n.getAttempts() + 1;
        String outcome;
        if (result.success()) {
            n.delivered(result.httpStatus(), now);
            outcome = "DELIVERED";
        } else if (result.permanent()) {
            n.skipped(result.error());
            outcome = "SKIPPED";
        } else {
            n.failed(result.httpStatus(), result.error(), now.plus(backoff.delayAfter(attemptNo)));
            boolean exhausted = n.getAttempts() >= config.maxAttempts()
                    || n.getCreatedAt().plus(config.maxAge()).isBefore(now);
            if (exhausted) {
                n.dead();
                outcome = "DEAD";
                publishFailure(n, now);
                log.warn("Notification {} ({} {}) gave up after {} attempts: {}", n.getId(), n.getChannel(),
                        n.getPublicEventType(), n.getAttempts(), result.error());
            } else {
                outcome = "RETRY";
                log.info("Notification {} ({} {}) attempt {} failed: {}; next at {}", n.getId(), n.getChannel(),
                        n.getPublicEventType(), n.getAttempts(), result.error(), n.getNextAttemptAt());
            }
        }
        notifications.save(n);
        store.recordAttempt(id, attemptNo, outcome, result.httpStatus(), result.error(), durationMs, now);
        meters.counter("notifications.attempts", "channel", n.getChannel().name(), "outcome", outcome).increment();
    }

    private void publishFailure(Notification n, Instant now) {
        NotificationEventPayload payload = new NotificationEventPayload(n.getId(), n.getMerchantId(),
                n.getSourceEventId(), n.getSourceEventType(), n.getChannel().name(), n.getAttempts(), n.getLastError(), now);
        long version = n.getVersion() == null ? 0 : n.getVersion() + 1;
        outbox.append(Topics.NOTIFICATION_EVENTS, "Notification", n.getId().toString(), version, n.getMerchantId(),
                NotificationEventPayload.NOTIFICATION_FAILED, NotificationEventPayload.CURRENT_VERSION,
                n.getSourceEventId().toString(), payload);
    }
}
