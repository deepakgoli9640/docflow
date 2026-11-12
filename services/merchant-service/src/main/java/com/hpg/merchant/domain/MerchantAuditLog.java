package com.hpg.merchant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only record of a security-relevant action. {@link Immutable} stops Hibernate from issuing
 * updates; a database trigger rejects UPDATE/DELETE from anywhere else.
 */
@Entity
@Immutable
@Table(name = "merchant_audit_log")
public class MerchantAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id")
    private UUID merchantId;

    @Column(name = "action", nullable = false, length = 50)
    private String action;

    @Column(name = "actor", nullable = false, length = 100)
    private String actor;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", nullable = false)
    private Map<String, Object> details;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MerchantAuditLog() {
        // for JPA
    }

    public MerchantAuditLog(UUID merchantId, AuditAction action, String actor, String correlationId,
            Map<String, Object> details, Instant createdAt) {
        this.merchantId = merchantId;
        this.action = action.name();
        this.actor = actor;
        this.correlationId = correlationId;
        this.details = Map.copyOf(details);
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getAction() {
        return action;
    }

    public String getActor() {
        return actor;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
