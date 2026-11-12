package com.hpg.payment.domain;

import com.hpg.common.crypto.SecureTokens;
import com.hpg.common.error.BusinessException;
import com.hpg.common.id.Ids;
import com.hpg.common.money.Money;
import com.hpg.persistence.AuditableEntity;
import com.hpg.persistence.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Aggregate root: the hospital's request to collect one amount for one bill. Owns the state machine.
 * All state changes go through {@link #transition}; there are no setters for status.
 */
@Entity
@Table(name = "payments")
public class Payment extends AuditableEntity {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "merchant_reference", nullable = false, length = 64)
    private String merchantReference;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "capture_mode", nullable = false, length = 10)
    private CaptureMode captureMode;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "customer_name", length = 100)
    private String customerName;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "customer_email_enc", length = 512)
    private String customerEmail;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "customer_phone_enc", length = 512)
    private String customerPhone;

    @Column(name = "return_url", length = 2048)
    private String returnUrl;

    /** Unguessable token in the patient's checkout link (256 bits). Not the payment id. */
    @Column(name = "checkout_token", nullable = false, length = 64)
    private String checkoutToken;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "authorized_at")
    private Instant authorizedAt;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Column(name = "last_verified_at")
    private Instant lastVerifiedAt;

    @Column(name = "late_capture", nullable = false)
    private boolean lateCapture;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    protected Payment() {
        // for JPA
    }

    public static Payment create(UUID merchantId, String merchantReference, Money amount, CaptureMode captureMode,
            String description, Customer customer, String returnUrl, int maxAttempts, Instant expiresAt,
            String correlationId) {
        Payment p = new Payment();
        p.id = Ids.newId();
        p.merchantId = merchantId;
        p.merchantReference = merchantReference;
        p.amount = amount.amount();
        p.currency = amount.currency();
        p.status = PaymentStatus.CREATED;
        p.captureMode = captureMode;
        p.description = description;
        if (customer != null) {
            p.customerName = customer.name();
            p.customerEmail = customer.email();
            p.customerPhone = customer.phone();
        }
        p.returnUrl = returnUrl;
        p.checkoutToken = "ck_" + SecureTokens.urlSafe(32);
        p.maxAttempts = maxAttempts;
        p.expiresAt = expiresAt;
        p.correlationId = correlationId;
        return p;
    }

    /**
     * Applies a transition according to {@link PaymentStateMachine}.
     *
     * @return what happened; {@code REJECT} never returns — it throws
     * @throws BusinessException INVALID_STATE_TRANSITION for disallowed merchant/system requests
     */
    public TransitionDecision transition(PaymentStatus target, TransitionSource source, Instant now) {
        TransitionDecision decision = PaymentStateMachine.decide(status, target, source.authority());
        switch (decision) {
            case REJECT -> throw new BusinessException(PaymentErrorCode.INVALID_STATE_TRANSITION,
                    "Cannot move payment from " + status + " to " + target + ".",
                    Map.of("currentStatus", status.name(),
                            "allowedTransitions", PaymentStateMachine.allowedTargets(status, source.authority())));
            case APPLY -> apply(target, now);
            case NO_OP, IGNORE_STALE -> {
                // nothing to change
            }
        }
        return decision;
    }

    private void apply(PaymentStatus target, Instant now) {
        if (PaymentStateMachine.isLateCapture(status, target)) {
            lateCapture = true;
        }
        if (target == PaymentStatus.AUTHORIZED && authorizedAt == null) {
            authorizedAt = now;
        }
        if (target == PaymentStatus.CAPTURED) {
            capturedAt = now;
            if (authorizedAt == null) {
                authorizedAt = now;
            }
            failureCode = null;
            failureReason = null;
        }
        status = target;
    }

    public void recordFailure(String code, String reason) {
        this.failureCode = code;
        this.failureReason = reason == null ? null : reason.substring(0, Math.min(500, reason.length()));
    }

    public void markVerified(Instant now) {
        this.lastVerifiedAt = now;
    }

    /** A patient can still pay: not terminal, not past expiry. */
    public boolean isPayableAt(Instant now) {
        return PaymentStatus.PAYABLE.contains(status) && expiresAt.isAfter(now);
    }

    public Money amount() {
        return new Money(amount, currency);
    }

    public Customer customer() {
        return customerName == null && customerEmail == null && customerPhone == null ? null
                : new Customer(customerName, customerEmail, customerPhone);
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getMerchantReference() {
        return merchantReference;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public CaptureMode getCaptureMode() {
        return captureMode;
    }

    public String getDescription() {
        return description;
    }

    public String getReturnUrl() {
        return returnUrl;
    }

    public String getCheckoutToken() {
        return checkoutToken;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getAuthorizedAt() {
        return authorizedAt;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public Instant getLastVerifiedAt() {
        return lastVerifiedAt;
    }

    public boolean isLateCapture() {
        return lateCapture;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    /** Patient contact details. {@link #toString()} is masked so it can never leak into logs. */
    public record Customer(String name, String email, String phone) {

        @Override
        public String toString() {
            return "Customer[****]";
        }
    }
}
