package com.hpg.transaction.domain;

import com.hpg.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A payment or refund as the merchant's transaction history shows it. Built only from events; the
 * status moves forward with each newer event ({@code sourceVersion} = aggregate version), so an event
 * delivered late can never move it backwards.
 */
@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    private UUID id;

    @Column(name = "transaction_ref", nullable = false, length = 30, updatable = false)
    private String transactionRef;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10, updatable = false)
    private TransactionType type;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "refund_id", updatable = false)
    private UUID refundId;

    @Column(name = "merchant_reference", length = 64)
    private String merchantReference;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TransactionStatus status;

    @Column(name = "provider", length = 20)
    private String provider;

    @Column(name = "provider_reference", length = 64)
    private String providerReference;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "late_capture", nullable = false)
    private boolean lateCapture;

    @Column(name = "source_version", nullable = false)
    private long sourceVersion;

    @Column(name = "initiated_at", nullable = false)
    private Instant initiatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "last_event_at", nullable = false)
    private Instant lastEventAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Transaction() {
        // for JPA
    }

    public Transaction(UUID id, String transactionRef, UUID merchantId, TransactionType type, UUID paymentId,
            UUID refundId, Money amount, TransactionStatus status, long sourceVersion, Instant occurredAt, Instant now) {
        this.id = id;
        this.transactionRef = transactionRef;
        this.merchantId = merchantId;
        this.type = type;
        this.paymentId = paymentId;
        this.refundId = refundId;
        this.amount = amount.amount();
        this.currency = amount.currency();
        this.status = status;
        this.sourceVersion = sourceVersion;
        this.initiatedAt = occurredAt;
        this.lastEventAt = occurredAt;
        this.createdAt = now;
        this.updatedAt = now;
        if (status.isSettledMoney()) {
            this.completedAt = occurredAt;
        }
    }

    /**
     * Applies an event's status if the event is not older than what we have.
     *
     * @return true if applied; false if the event was stale (it still appears in the timeline)
     */
    public boolean applyStatus(TransactionStatus newStatus, long eventVersion, Instant occurredAt, Instant now) {
        if (eventVersion < sourceVersion) {
            return false;
        }
        this.status = newStatus;
        this.sourceVersion = eventVersion;
        this.lastEventAt = occurredAt;
        this.updatedAt = now;
        if (newStatus.isSettledMoney() && completedAt == null) {
            this.completedAt = occurredAt;
        }
        if (occurredAt.isBefore(initiatedAt)) {
            this.initiatedAt = occurredAt;
        }
        return true;
    }

    /** Facts that only get more complete over time (never erased by a later event lacking them). */
    public void enrich(String merchantReference, String provider, String providerReference, String failureCode,
            String failureReason, boolean lateCapture) {
        if (merchantReference != null) {
            this.merchantReference = merchantReference;
        }
        if (provider != null) {
            this.provider = provider;
        }
        if (providerReference != null) {
            this.providerReference = providerReference;
        }
        if (failureCode != null) {
            this.failureCode = failureCode;
            this.failureReason = failureReason == null || failureReason.length() <= 500 ? failureReason
                    : failureReason.substring(0, 500);
        }
        this.lateCapture = this.lateCapture || lateCapture;
    }

    public Money amount() {
        return new Money(amount, currency);
    }

    public UUID getId() {
        return id;
    }

    public String getTransactionRef() {
        return transactionRef;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public TransactionType getType() {
        return type;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getRefundId() {
        return refundId;
    }

    public String getMerchantReference() {
        return merchantReference;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public boolean isLateCapture() {
        return lateCapture;
    }

    public long getSourceVersion() {
        return sourceVersion;
    }

    public Instant getInitiatedAt() {
        return initiatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getLastEventAt() {
        return lastEventAt;
    }
}
