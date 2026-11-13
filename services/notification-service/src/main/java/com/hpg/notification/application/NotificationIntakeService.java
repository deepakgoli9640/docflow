package com.hpg.notification.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.hpg.notification.domain.Channel;
import com.hpg.notification.domain.MerchantNotificationReplica;
import com.hpg.notification.domain.Notification;
import com.hpg.notification.domain.RecipientType;
import com.hpg.notification.infrastructure.persistence.MerchantNotificationReplicaRepository;
import com.hpg.notification.infrastructure.persistence.NotificationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Turns a payment event into the set of notifications it should produce. Runs inside the consumer's
 * transaction, so claiming the event and creating its notifications commit together.
 *
 * <p>Creating rows is all that happens here. Nothing is sent on the Kafka thread: delivery can take ten
 * seconds and may be retried for a day, and a consumer thread that waited for that would stall the
 * partition behind it.
 */
@Service
public class NotificationIntakeService {

    private static final Logger log = LoggerFactory.getLogger(NotificationIntakeService.class);

    /** Events a hospital's system is told about. AUTHORIZED matters when the hospital captures manually. */
    private static final Set<String> MERCHANT_EVENTS = Set.of("PaymentAuthorized", "PaymentCaptured",
            "PaymentFailed", "PaymentCancelled", "PaymentExpired");
    /** Events a patient is told about. A patient does not need to hear about an internal authorization. */
    private static final Set<String> PATIENT_EMAIL_EVENTS = Set.of("PaymentCaptured", "PaymentFailed",
            "PaymentExpired");
    /** SMS costs money per message and interrupts people: receipts only. */
    private static final Set<String> PATIENT_SMS_EVENTS = Set.of("PaymentCaptured");

    private final NotificationRepository notifications;
    private final MerchantNotificationReplicaRepository merchants;
    private final MeterRegistry meters;
    private final Clock clock;

    public NotificationIntakeService(NotificationRepository notifications,
            MerchantNotificationReplicaRepository merchants, MeterRegistry meters, Clock clock) {
        this.notifications = notifications;
        this.merchants = merchants;
        this.meters = meters;
        this.clock = clock;
    }

    /**
     * @param sequenceNo the payment's version at the time of the event, passed on to the hospital so it
     *                   can ignore an update older than one it has already applied
     * @return the notifications created (an empty list if this event needs none)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Notification> intake(UUID eventId, String eventType, UUID merchantId, long sequenceNo,
            JsonNode payload) {
        MerchantNotificationReplica merchant = merchants.findById(merchantId).orElse(null);
        if (merchant == null) {
            // The merchant snapshot has not arrived yet. Retrying lets the replica catch up; the Kafka
            // error handler backs off and eventually dead-letters, which is the right signal that
            // something is wrong with replication rather than with this payment.
            throw new MerchantReplicaMissingException(merchantId);
        }
        UUID paymentId = payload.hasNonNull("paymentId") ? UUID.fromString(payload.get("paymentId").asText()) : null;
        JsonNode customer = payload.path("customer");
        List<Notification> created = new ArrayList<>();

        if (MERCHANT_EVENTS.contains(eventType)) {
            if (merchant.getWebhookUrl() != null && !merchant.getWebhookUrl().isBlank()) {
                add(created, eventId, eventType, Channel.WEBHOOK, RecipientType.MERCHANT, merchant.getWebhookUrl(),
                        merchantId, paymentId, sequenceNo, payload);
            } else {
                meters.counter("notifications.skipped", "channel", "WEBHOOK", "reason", "no_webhook_url").increment();
            }
        }
        if (PATIENT_EMAIL_EVENTS.contains(eventType) && merchant.isNotifyPatientEmail()) {
            String email = customer.path("email").asText(null);
            if (email != null && !email.isBlank()) {
                add(created, eventId, eventType, Channel.EMAIL, RecipientType.PATIENT, email, merchantId, paymentId,
                        sequenceNo, payload);
            }
        }
        if (PATIENT_SMS_EVENTS.contains(eventType) && merchant.isNotifyPatientSms()) {
            String phone = customer.path("phone").asText(null);
            if (phone != null && !phone.isBlank()) {
                add(created, eventId, eventType, Channel.SMS, RecipientType.PATIENT, phone, merchantId, paymentId,
                        sequenceNo, payload);
            }
        }
        if (!created.isEmpty()) {
            log.info("Queued {} notification(s) for {} paymentId={}", created.size(), eventType, paymentId);
        }
        return created;
    }

    private void add(List<Notification> created, UUID eventId, String eventType, Channel channel,
            RecipientType recipientType, String recipient, UUID merchantId, UUID paymentId, long sequenceNo,
            JsonNode payload) {
        // The unique constraint on (source_event_id, channel, recipient_type) is the real guarantee that
        // one event produces one message per recipient. Checking first keeps the common redelivery case
        // from failing the whole transaction on a constraint violation.
        if (notifications.findBySourceEventIdAndChannelAndRecipientType(eventId, channel, recipientType).isPresent()) {
            meters.counter("notifications.duplicates_prevented", "channel", channel.name()).increment();
            log.debug("Notification for eventId={} channel={} already exists; not creating another",
                    eventId, channel);
            return;
        }
        Notification notification = notifications.save(Notification.create(eventId, eventType, channel, recipientType,
                recipient, merchantId, paymentId, sequenceNo, payload, clock.instant()));
        created.add(notification);
        meters.counter("notifications.created", "channel", channel.name(), "event", eventType).increment();
    }

    /** Thrown when the merchant replica has not caught up yet; the consumer retries. */
    public static class MerchantReplicaMissingException extends RuntimeException {
        public MerchantReplicaMissingException(UUID merchantId) {
            super("No merchant replica for " + merchantId + " yet; waiting for its snapshot");
        }
    }
}
