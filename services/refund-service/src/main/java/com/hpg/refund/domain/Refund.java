package com.hpg.refund.domain;

import com.hpg.common.id.Ids;
import com.hpg.common.money.Money;
import com.hpg.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A refund of (part of) a captured payment. State changes only through {@link #transition}. */
@Entity
@Table(name = "refunds")
public class Refund extends AuditableEntity {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RefundStatus status;

    @Column(name = "reason", length = 255)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "initiated_by", nullable = false, length = 10)
    private InitiatedBy initiatedBy;

    @Column(name = "provider", nullable = false, length = 20)
    private String provider;

    @Column(name = "provider_refund_id", length = 64)
    private String providerRefundId;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "provider_attempts", nullable = false)
    private int providerAttempts;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    protected Refund() {
        // for JPA
    }

    public Refund(UUID paymentId, UUID merchantId, Money amount, String reason, InitiatedBy initiatedBy,
            String provider, String correlationId) {
        this.id = Ids.newId();
        this.paymentId = paymentId;
        this.merchantId = merchantId;
        this.amount = amount.amount();
        this.currency = amount.currency();
        this.status = RefundStatus.CREATED;
        this.reason = reason;
        this.initiatedBy = initiatedBy;
        this.provider = provider;
        this.correlationId = correlationId;
    }

    /**
     * @return true if the state changed; false if {@code target} is the current state or not reachable
     *         (e.g. late evidence for a refund that is already final)
     */
    public boolean transition(RefundStatus target, Instant now) {
        if (status == target || !status.canTransitionTo(target)) {
            return false;
        }
        status = target;
        if (target == RefundStatus.COMPLETED) {
            completedAt = now;
        }
        return true;
    }

    public void recordProviderAttempt(Instant now) {
        providerAttempts++;
        lastAttemptAt = now;
    }

    public void adoptProviderRefundId(String providerRefundId) {
        if (providerRefundId != null && this.providerRefundId == null) {
            this.providerRefundId = providerRefundId;
        }
    }

    public void recordFailure(String code, String reason) {
        this.failureCode = code;
        this.failureReason = reason == null || reason.length() <= 500 ? reason : reason.substring(0, 500);
    }

    public Money amount() {
        return new Money(amount, currency);
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public RefundStatus getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public InitiatedBy getInitiatedBy() {
        return initiatedBy;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderRefundId() {
        return providerRefundId;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public int getProviderAttempts() {
        return providerAttempts;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
