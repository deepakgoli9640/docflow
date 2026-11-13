package com.hpg.notification.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.hpg.common.id.Ids;
import com.hpg.persistence.AuditableEntity;
import com.hpg.persistence.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One thing to tell one recipient, and the record of how that went.
 *
 * <p>A notification exists as a row before anything is sent, which is what makes delivery survivable:
 * retries span hours, so they cannot live on a Kafka consumer thread or in memory. The consumer's only
 * job is to create these rows; a scheduler delivers them on their own timetable.
 */
@Entity
@Table(name = "notifications")
public class Notification extends AuditableEntity {

    @Id
    private UUID id;

    @Column(name = "source_event_id", nullable = false)
    private UUID sourceEventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private Channel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "recipient_type", nullable = false, length = 20)
    private RecipientType recipientType;

    /** A patient's email or phone, or the hospital's webhook URL. Encrypted at rest. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "recipient_enc", length = 2048)
    private String recipient;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "sequence_no", nullable = false)
    private long sequenceNo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private JsonNode payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private NotificationStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    protected Notification() {
        // for JPA
    }

    public static Notification create(UUID sourceEventId, String eventType, Channel channel,
            RecipientType recipientType, String recipient, UUID merchantId, UUID paymentId, long sequenceNo,
            JsonNode payload, Instant now) {
        Notification notification = new Notification();
        notification.id = Ids.newId();
        notification.sourceEventId = sourceEventId;
        notification.eventType = eventType;
        notification.channel = channel;
        notification.recipientType = recipientType;
        notification.recipient = recipient;
        notification.merchantId = merchantId;
        notification.paymentId = paymentId;
        notification.sequenceNo = sequenceNo;
        notification.payload = payload;
        notification.status = NotificationStatus.PENDING;
        notification.nextAttemptAt = now;
        return notification;
    }

    /**
     * Marks this notification as being delivered right now, so a second dispatcher leaves it alone.
     * The attempt is counted here, before the attempt is made: a crash mid-delivery must cost an attempt,
     * otherwise a notification that crashes the dispatcher would be retried for ever.
     */
    public void claim(Instant now) {
        this.status = NotificationStatus.SENDING;
        this.attemptCount++;
        this.nextAttemptAt = null;
    }

    public NotificationAttempt recordSuccess(long durationMs, Integer responseCode, Instant now) {
        this.status = NotificationStatus.DELIVERED;
        this.deliveredAt = now;
        this.lastError = null;
        return attempt(DeliveryOutcome.DELIVERED, responseCode, null, durationMs, now);
    }

    /**
     * @param nextAttemptAt when to try again, or {@code null} when the retry budget is spent — the
     *                      notification then becomes DEAD and an event is published for alerting
     */
    public NotificationAttempt recordRetryableFailure(String error, Integer responseCode, long durationMs,
            Instant nextAttemptAt, Instant now) {
        this.lastError = truncate(error);
        if (nextAttemptAt == null) {
            this.status = NotificationStatus.DEAD;
            this.nextAttemptAt = null;
        } else {
            this.status = NotificationStatus.RETRYING;
            this.nextAttemptAt = nextAttemptAt;
        }
        return attempt(DeliveryOutcome.RETRYABLE_FAILURE, responseCode, error, durationMs, now);
    }

    public NotificationAttempt recordPermanentFailure(String error, Integer responseCode, long durationMs,
            Instant now) {
        this.status = NotificationStatus.DEAD;
        this.nextAttemptAt = null;
        this.lastError = truncate(error);
        return attempt(DeliveryOutcome.PERMANENT_FAILURE, responseCode, error, durationMs, now);
    }

    /** Nothing to deliver, recorded rather than silently dropped. */
    public void skip(String reason) {
        this.status = NotificationStatus.SKIPPED;
        this.nextAttemptAt = null;
        this.lastError = truncate(reason);
    }

    /** Ops action after the hospital fixed its endpoint: put a dead notification back in the queue. */
    public void requeue(Instant now) {
        this.status = NotificationStatus.PENDING;
        this.nextAttemptAt = now;
        this.lastError = null;
    }

    private NotificationAttempt attempt(DeliveryOutcome outcome, Integer responseCode, String error, long durationMs,
            Instant now) {
        return new NotificationAttempt(id, attemptCount, outcome, responseCode, error, durationMs, now);
    }

    private static String truncate(String value) {
        return value == null ? null : value.substring(0, Math.min(1000, value.length()));
    }

    public UUID getId() {
        return id;
    }

    public UUID getSourceEventId() {
        return sourceEventId;
    }

    public String getEventType() {
        return eventType;
    }

    public Channel getChannel() {
        return channel;
    }

    public RecipientType getRecipientType() {
        return recipientType;
    }

    public String getRecipient() {
        return recipient;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public long getSequenceNo() {
        return sequenceNo;
    }

    public JsonNode getPayload() {
        return payload;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    /** Recipient and payload are left out: they hold patient contact details. */
    @Override
    public String toString() {
        return "Notification[" + id + " " + channel + "/" + recipientType + " " + eventType + " " + status + "]";
    }
}
