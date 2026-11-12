package com.hpg.payment.domain;

import com.hpg.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * payment-service's local, read-only copy of the merchant settings it needs on every payment request.
 * Owned by merchant-service; replicated through events (Phase 4) so that creating a payment never
 * requires a synchronous call to merchant-service. {@code snapshotVersion} makes updates idempotent
 * and lets older snapshots be ignored.
 */
@Entity
@Table(name = "merchant_config_replica")
public class MerchantConfigReplica {

    @Id
    @Column(name = "merchant_id")
    private UUID merchantId;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "enabled_methods", nullable = false)
    private Set<String> enabledMethods;

    @Enumerated(EnumType.STRING)
    @Column(name = "capture_mode", nullable = false, length = 10)
    private CaptureMode captureMode;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "min_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal minAmount;

    @Column(name = "max_amount_per_txn", nullable = false, precision = 19, scale = 2)
    private BigDecimal maxAmountPerTxn;

    @Column(name = "daily_limit", nullable = false, precision = 19, scale = 2)
    private BigDecimal dailyLimit;

    @Column(name = "payment_ttl_minutes", nullable = false)
    private int paymentTtlMinutes;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "snapshot_version", nullable = false)
    private long snapshotVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MerchantConfigReplica() {
        // for JPA
    }

    public MerchantConfigReplica(UUID merchantId) {
        this.merchantId = merchantId;
    }

    /** Applies a snapshot unless it is older than what we already have. @return true if applied */
    public boolean apply(Snapshot s, Instant now) {
        if (updatedAt != null && s.version() <= snapshotVersion) {
            return false;
        }
        this.displayName = s.displayName();
        this.status = s.status();
        this.enabledMethods = Set.copyOf(s.enabledMethods());
        this.captureMode = s.captureMode();
        this.currency = s.minAmount().currency();
        this.minAmount = s.minAmount().amount();
        this.maxAmountPerTxn = s.maxAmountPerTransaction().amount();
        this.dailyLimit = s.dailyLimit().amount();
        this.paymentTtlMinutes = s.paymentTtlMinutes();
        this.maxAttempts = s.maxAttempts();
        this.snapshotVersion = s.version();
        this.updatedAt = now;
        return true;
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getStatus() {
        return status;
    }

    public Set<String> getEnabledMethods() {
        return enabledMethods;
    }

    public CaptureMode getCaptureMode() {
        return captureMode;
    }

    public String getCurrency() {
        return currency;
    }

    public Money minAmount() {
        return new Money(minAmount, currency);
    }

    public Money maxAmountPerTxn() {
        return new Money(maxAmountPerTxn, currency);
    }

    public Money dailyLimit() {
        return new Money(dailyLimit, currency);
    }

    public int getPaymentTtlMinutes() {
        return paymentTtlMinutes;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public long getSnapshotVersion() {
        return snapshotVersion;
    }

    public record Snapshot(long version, String displayName, String status, Set<String> enabledMethods, CaptureMode captureMode,
            Money minAmount, Money maxAmountPerTransaction, Money dailyLimit, int paymentTtlMinutes, int maxAttempts) {

        public Snapshot withVersion(long newVersion) {
            return new Snapshot(newVersion, displayName, status, enabledMethods, captureMode, minAmount,
                    maxAmountPerTransaction, dailyLimit, paymentTtlMinutes, maxAttempts);
        }
    }
}
