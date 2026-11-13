package com.hpg.refund.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One state change of a refund. Append-only (a database trigger rejects updates and deletes). */
@Entity
@Table(name = "refund_status_history")
public class RefundStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "refund_id", nullable = false)
    private UUID refundId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20)
    private RefundStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private RefundStatus toStatus;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "actor", nullable = false, length = 100)
    private String actor;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RefundStatusHistory() {
        // for JPA
    }

    public RefundStatusHistory(UUID refundId, RefundStatus fromStatus, RefundStatus toStatus, String reason,
            String actor, Instant createdAt) {
        this.refundId = refundId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason == null || reason.length() <= 500 ? reason : reason.substring(0, 500);
        this.actor = actor;
        this.createdAt = createdAt;
    }

    public RefundStatus getFromStatus() {
        return fromStatus;
    }

    public RefundStatus getToStatus() {
        return toStatus;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
