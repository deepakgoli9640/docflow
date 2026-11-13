package com.hpg.notification.domain;

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

import java.time.Instant;
import java.util.UUID;

/**
 * Something we owe someone: a webhook to a hospital or an email to a patient. Created by the event
 * intake (one per event and channel), delivered by the dispatcher with retries.
 */
@Entity
@Table(name = "notifications")
public class Notification extends AuditableEntity {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(name = "source_event_id", nullable = false, updatable = false)
    private UUID sourceEventId;

    @Column(name = "source_event_type", nullable = false, length = 50, updatable = false)
    private String sourceEventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 10, updatable = false)
    private Channel channel;

    @Column(name = "public_event_type", nullable = false, length = 50, updatable = false)
    private String publicEventType;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "refund_id", updatable = false)
    private UUID refundId;

    @Column(name = "sequence", nullable = false, updatable = false)
    private long sequence;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "recipient_enc", length = 512, updatable = false)
    private String recipient;

    @Column(name = "subject", length = 200, updatable = false)
    private String subject;

    @Column(name = "body", nullable = false, updatable = false)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private NotificationStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "last_response_status")
    private Integer lastResponseStatus;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    protected Notification() {
        // for JPA
    }

    public Notification(UUID merchantId, UUID sourceEventId, String sourceEventType, Channel channel,
            String publicEventType, UUID paymentId, UUID refundId, long sequence, String recipient, String subject,
            String body, Instant now) {
        this.id = Ids.newId();
        this.merchantId = merchantId;
        this.sourceEventId = sourceEventId;
        this.sourceEventType = sourceEventType;
        this.channel = channel;
        this.publicEventType = publicEventType;
        this.paymentId = paymentId;
        this.refundId = refundId;
        this.sequence = sequence;
        this.recipient = recipient;
        this.subject = subject;
        this.body = body;
        this.status = NotificationStatus.PENDING;
        this.nextAttemptAt = now;
    }

    public void delivered(Integer httpStatus, Instant now) {
        attempts++;
        status = NotificationStatus.DELIVERED;
        lastResponseStatus = httpStatus;
        lastError = null;
        deliveredAt = now;
    }

    public void skipped(String reason) {
        status = NotificationStatus.SKIPPED;
        lastError = truncate(reason);
    }

    public void failed(Integer httpStatus, String error, Instant nextAttemptAt) {
        attempts++;
        lastResponseStatus = httpStatus;
        lastError = truncate(error);
        this.nextAttemptAt = nextAttemptAt;
    }

    public void dead() {
        status = NotificationStatus.DEAD;
    }

    /** OPS re-queues a DEAD notification (e.g. after the hospital fixed its endpoint). */
    public void requeue(Instant now) {
        status = NotificationStatus.PENDING;
        nextAttemptAt = now;
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500);
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public UUID getSourceEventId() {
        return sourceEventId;
    }

    public String getSourceEventType() {
        return sourceEventType;
    }

    public Channel getChannel() {
        return channel;
    }

    public String getPublicEventType() {
        return publicEventType;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getRefundId() {
        return refundId;
    }

    public long getSequence() {
        return sequence;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Integer getLastResponseStatus() {
        return lastResponseStatus;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }
}
