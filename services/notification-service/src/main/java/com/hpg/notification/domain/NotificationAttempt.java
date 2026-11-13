package com.hpg.notification.domain;

import com.hpg.common.id.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/**
 * One delivery attempt: when we tried, what happened, how long it took. Append-only (enforced by a
 * database trigger) because this is the evidence of what a hospital was told and when.
 */
@Entity
@Immutable
@Table(name = "notification_attempts")
public class NotificationAttempt {

    @Id
    private UUID id;

    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 20)
    private DeliveryOutcome outcome;

    @Column(name = "response_code")
    private Integer responseCode;

    @Column(name = "error", length = 1000)
    private String error;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;

    protected NotificationAttempt() {
        // for JPA
    }

    public NotificationAttempt(UUID notificationId, int attemptNo, DeliveryOutcome outcome, Integer responseCode,
            String error, long durationMs, Instant attemptedAt) {
        this.id = Ids.newId();
        this.notificationId = notificationId;
        this.attemptNo = attemptNo;
        this.outcome = outcome;
        this.responseCode = responseCode;
        this.error = error == null ? null : error.substring(0, Math.min(1000, error.length()));
        this.durationMs = durationMs;
        this.attemptedAt = attemptedAt;
    }

    public UUID getNotificationId() {
        return notificationId;
    }

    public int getAttemptNo() {
        return attemptNo;
    }

    public DeliveryOutcome getOutcome() {
        return outcome;
    }

    public Integer getResponseCode() {
        return responseCode;
    }

    public String getError() {
        return error;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public Instant getAttemptedAt() {
        return attemptedAt;
    }
}
