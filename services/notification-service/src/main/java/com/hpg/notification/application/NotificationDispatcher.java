package com.hpg.notification.application;

import com.hpg.events.Topics;
import com.hpg.events.notification.NotificationEventType;
import com.hpg.events.notification.NotificationFailedPayload;
import com.hpg.messaging.outbox.OutboxWriter;
import com.hpg.notification.delivery.DeliveryChannel;
import com.hpg.notification.delivery.DeliveryResult;
import com.hpg.notification.domain.Channel;
import com.hpg.notification.domain.DeliveryOutcome;
import com.hpg.notification.domain.MerchantNotificationReplica;
import com.hpg.notification.domain.Notification;
import com.hpg.notification.domain.NotificationAttempt;
import com.hpg.notification.domain.NotificationStatus;
import com.hpg.notification.infrastructure.persistence.MerchantNotificationReplicaRepository;
import com.hpg.notification.infrastructure.persistence.NotificationAttemptRepository;
import com.hpg.notification.infrastructure.persistence.NotificationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Delivers the notifications that are due.
 *
 * <p>Each notification is handled in three steps, deliberately not in one transaction:
 * <ol>
 *   <li>claim it (status SENDING) and commit — so a second dispatcher, or the next run, leaves it alone;</li>
 *   <li>deliver it, with no transaction open — the call can take seconds, and holding a database
 *       connection and row lock for a slow hospital endpoint is how a connection pool runs dry;</li>
 *   <li>record the attempt and the next schedule, and commit.</li>
 * </ol>
 * If the process dies between (1) and (3) the row stays SENDING; a separate sweep picks it up after a
 * timeout. That can deliver twice, which is why each webhook carries an event id for the hospital to
 * deduplicate on — losing a notification would be the worse failure.
 */
@Service
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationRepository notifications;
    private final NotificationAttemptRepository attempts;
    private final MerchantNotificationReplicaRepository merchants;
    private final Map<Channel, DeliveryChannel> channels = new EnumMap<>(Channel.class);
    private final RetryPolicy retryPolicy;
    private final NotificationProperties properties;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;
    private final Clock clock;

    public NotificationDispatcher(NotificationRepository notifications, NotificationAttemptRepository attempts,
            MerchantNotificationReplicaRepository merchants, List<DeliveryChannel> deliveryChannels,
            RetryPolicy retryPolicy, NotificationProperties properties, OutboxWriter outbox,
            TransactionTemplate tx, MeterRegistry meters, Clock clock) {
        this.notifications = notifications;
        this.attempts = attempts;
        this.merchants = merchants;
        deliveryChannels.forEach(channel -> this.channels.put(channel.channel(), channel));
        this.retryPolicy = retryPolicy;
        this.properties = properties;
        this.outbox = outbox;
        this.tx = tx;
        this.meters = meters;
        this.clock = clock;
    }

    /** @return how many notifications were attempted */
    public int dispatchDue() {
        List<UUID> due = notifications.findDueIds(clock.instant(),
                PageRequest.of(0, properties.dispatchBatchSize()));
        return dispatch(due);
    }

    /** Recovers notifications left in SENDING by a dispatcher that died mid-delivery. */
    public int recoverStuck() {
        List<UUID> stuck = notifications.findStuckSendingIds(
                clock.instant().minus(properties.stuckSendingAfter()),
                PageRequest.of(0, properties.dispatchBatchSize()));
        if (!stuck.isEmpty()) {
            log.warn("Retrying {} notification(s) left in SENDING for more than {}", stuck.size(),
                    properties.stuckSendingAfter());
        }
        return dispatch(stuck);
    }

    private int dispatch(List<UUID> ids) {
        int attempted = 0;
        for (UUID id : ids) {
            try {
                if (deliverOne(id)) {
                    attempted++;
                }
            } catch (RuntimeException e) {
                // One bad notification must not stop the batch; it stays SENDING and the sweep retries it.
                log.error("Dispatching notification {} failed unexpectedly", id, e);
                meters.counter("notifications.dispatch_errors").increment();
            }
        }
        return attempted;
    }

    private boolean deliverOne(UUID id) {
        Notification claimed = tx.execute(status -> notifications.findById(id).map(notification -> {
            if (notification.getStatus().isTerminal()) {
                return null; // already handled by another run
            }
            notification.claim(clock.instant());
            return notification;
        }).orElse(null));
        if (claimed == null) {
            return false;
        }

        MerchantNotificationReplica merchant = merchants.findById(claimed.getMerchantId()).orElse(null);
        DeliveryChannel channel = channels.get(claimed.getChannel());
        Timer.Sample sample = Timer.start(meters);
        final DeliveryResult result = attempt(claimed, merchant, channel);
        long durationMs = sample.stop(Timer.builder("notifications.delivery")
                .tag("channel", claimed.getChannel().name())
                .tag("outcome", result.outcome().name())
                .register(meters)) / 1_000_000;

        tx.executeWithoutResult(status -> record(claimed.getId(), result, durationMs));
        return true;
    }

    private DeliveryResult attempt(Notification notification, MerchantNotificationReplica merchant,
            DeliveryChannel channel) {
        if (channel == null) {
            return DeliveryResult.permanent("no delivery channel configured for " + notification.getChannel(), null);
        }
        try {
            return channel.deliver(notification, merchant);
        } catch (RuntimeException e) {
            // A channel should classify its own failures; if one throws anyway, treat it as retryable
            // rather than losing the notification, and make the bug visible in the logs.
            log.warn("Delivery of notification {} threw", notification.getId(), e);
            return DeliveryResult.retryable(e.getClass().getSimpleName() + ": " + e.getMessage(), null);
        }
    }

    private void record(UUID notificationId, DeliveryResult result, long durationMs) {
        Notification notification = notifications.findById(notificationId).orElseThrow();
        Instant now = clock.instant();
        NotificationAttempt attempt = switch (result.outcome()) {
            case DELIVERED -> notification.recordSuccess(durationMs, result.responseCode(), now);
            case PERMANENT_FAILURE -> notification.recordPermanentFailure(result.error(), result.responseCode(),
                    durationMs, now);
            case RETRYABLE_FAILURE -> notification.recordRetryableFailure(result.error(), result.responseCode(),
                    durationMs, retryPolicy.nextAttemptAt(notification.getAttemptCount(), now), now);
        };
        attempts.save(attempt);

        if (result.outcome() == DeliveryOutcome.DELIVERED) {
            log.info("Delivered notification {} ({} to {}) on attempt {}", notification.getId(),
                    notification.getChannel(), notification.getRecipientType(), notification.getAttemptCount());
            return;
        }
        if (notification.getStatus() == NotificationStatus.DEAD) {
            // Nobody is told anything further by retrying. Publish it so it can be alerted on, and so an
            // operator can see which hospital stopped accepting callbacks.
            log.error("Notification {} is DEAD after {} attempt(s): {}", notification.getId(),
                    notification.getAttemptCount(), notification.getLastError());
            meters.counter("notifications.dead", "channel", notification.getChannel().name()).increment();
            outbox.append(Topics.NOTIFICATION_EVENTS, "Notification", notification.getId().toString(),
                    notification.getAttemptCount(), notification.getMerchantId(),
                    NotificationEventType.NotificationFailed.name(), NotificationEventType.CURRENT_VERSION,
                    notification.getSourceEventId().toString(),
                    new NotificationFailedPayload(notification.getId(), notification.getMerchantId(),
                            notification.getPaymentId(), notification.getSourceEventId(),
                            notification.getEventType(), notification.getChannel().name(),
                            notification.getRecipientType().name(), notification.getAttemptCount(),
                            notification.getLastError(), now));
        } else {
            log.info("Notification {} failed (attempt {}), next attempt at {}: {}", notification.getId(),
                    notification.getAttemptCount(), notification.getNextAttemptAt(), notification.getLastError());
        }
    }

    /** Used by tests and the admin API to inspect what was attempted. */
    public List<NotificationAttempt> attemptsOf(UUID notificationId) {
        return attempts.findByNotificationIdOrderByAttemptNoAsc(notificationId);
    }

    public Optional<Notification> find(UUID id) {
        return notifications.findById(id);
    }
}
