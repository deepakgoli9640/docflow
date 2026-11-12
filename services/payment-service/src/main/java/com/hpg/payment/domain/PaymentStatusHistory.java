package com.hpg.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/** One row per state change: from, to, what triggered it, and who. Append-only (DB trigger). */
@Entity
@Immutable
@Table(name = "payment_status_history")
public class PaymentStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20)
    private PaymentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private PaymentStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 30)
    private TransitionSource source;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "causation_id", length = 100)
    private String causationId;

    @Column(name = "actor", nullable = false, length = 100)
    private String actor;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PaymentStatusHistory() {
        // for JPA
    }

    public PaymentStatusHistory(UUID paymentId, PaymentStatus fromStatus, PaymentStatus toStatus,
            TransitionSource source, String reason, String causationId, String actor, Instant createdAt) {
        this.paymentId = paymentId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.source = source;
        this.reason = reason == null ? null : reason.substring(0, Math.min(500, reason.length()));
        this.causationId = causationId;
        this.actor = actor;
        this.createdAt = createdAt;
    }

    public PaymentStatus getFromStatus() {
        return fromStatus;
    }

    public PaymentStatus getToStatus() {
        return toStatus;
    }

    public TransitionSource getSource() {
        return source;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
