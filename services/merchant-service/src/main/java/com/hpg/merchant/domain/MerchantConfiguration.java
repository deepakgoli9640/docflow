package com.hpg.merchant.domain;

import com.hpg.common.money.Money;
import com.hpg.merchant.infrastructure.persistence.EncryptedStringConverter;
import jakarta.persistence.Convert;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Per-merchant payment configuration. One row per merchant, sharing the merchant's id.
 *
 * <p>Validation against platform maximums happens in the application service (it needs configuration
 * properties); this entity guards only its own invariants.
 */
@Entity
@Table(name = "merchant_configurations")
public class MerchantConfiguration extends AuditableEntity {

    @Id
    @Column(name = "merchant_id")
    private UUID merchantId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "enabled_methods", nullable = false)
    private Set<PaymentMethod> enabledMethods;

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

    @Column(name = "refund_window_days", nullable = false)
    private int refundWindowDays;

    @Column(name = "webhook_url", length = 2048)
    private String webhookUrl;

    /** Encrypted by {@code EncryptedStringConverter}; holds ciphertext in the database. */
    @Column(name = "webhook_secret_enc", length = 512)
    @Convert(converter = EncryptedStringConverter.class)
    private String webhookSecret;

    @Column(name = "notify_patient_email", nullable = false)
    private boolean notifyPatientEmail;

    @Column(name = "notify_patient_sms", nullable = false)
    private boolean notifyPatientSms;

    protected MerchantConfiguration() {
        // for JPA
    }

    public MerchantConfiguration(UUID merchantId, PaymentSettings settings, String webhookSecret) {
        this.merchantId = merchantId;
        this.webhookSecret = webhookSecret;
        apply(settings);
    }

    public final void apply(PaymentSettings settings) {
        this.enabledMethods = EnumSet.copyOf(settings.enabledMethods());
        this.captureMode = settings.captureMode();
        this.currency = settings.minAmount().currency();
        this.minAmount = settings.minAmount().amount();
        this.maxAmountPerTxn = settings.maxAmountPerTransaction().amount();
        this.dailyLimit = settings.dailyLimit().amount();
        this.paymentTtlMinutes = settings.paymentTtlMinutes();
        this.maxAttempts = settings.maxAttempts();
        this.refundWindowDays = settings.refundWindowDays();
        this.webhookUrl = settings.webhookUrl();
        this.notifyPatientEmail = settings.notifyPatientEmail();
        this.notifyPatientSms = settings.notifyPatientSms();
    }

    public void rotateWebhookSecret(String newSecret) {
        this.webhookSecret = newSecret;
    }

    public PaymentSettings settings() {
        return new PaymentSettings(EnumSet.copyOf(enabledMethods), captureMode, new Money(minAmount, currency),
                new Money(maxAmountPerTxn, currency), new Money(dailyLimit, currency), paymentTtlMinutes,
                maxAttempts, refundWindowDays, webhookUrl, notifyPatientEmail, notifyPatientSms);
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }
}
