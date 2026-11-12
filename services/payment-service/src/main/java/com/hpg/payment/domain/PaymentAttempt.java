package com.hpg.payment.domain;

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
import java.util.UUID;

/**
 * One try by the patient on the checkout (a provider payment id such as {@code pay_...}). A failed
 * attempt does not fail the payment; the patient may try again until {@code maxAttempts}.
 */
@Entity
@Table(name = "payment_attempts")
public class PaymentAttempt extends AuditableEntity {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "payment_order_id", nullable = false)
    private UUID paymentOrderId;

    @Column(name = "provider_payment_id", nullable = false, length = 64)
    private String providerPaymentId;

    @Column(name = "method", length = 30)
    private String method;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AttemptStatus status;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_description", length = 500)
    private String errorDescription;

    @Column(name = "duplicate_capture", nullable = false)
    private boolean duplicateCapture;

    protected PaymentAttempt() {
        // for JPA
    }

    public PaymentAttempt(UUID paymentId, UUID paymentOrderId, String providerPaymentId, String method, Money amount) {
        this.id = Ids.newId();
        this.paymentId = paymentId;
        this.paymentOrderId = paymentOrderId;
        this.providerPaymentId = providerPaymentId;
        this.method = method;
        this.status = AttemptStatus.CREATED;
        this.amount = amount.amount();
        this.currency = amount.currency();
    }

    /** @return true if the status actually changed */
    public boolean advanceTo(AttemptStatus next, String method, String errorCode, String errorDescription) {
        if (method != null) {
            this.method = method;
        }
        if (!status.canMoveTo(next)) {
            return false;
        }
        this.status = next;
        if (next == AttemptStatus.FAILED) {
            this.errorCode = errorCode;
            this.errorDescription = errorDescription == null ? null
                    : errorDescription.substring(0, Math.min(500, errorDescription.length()));
        }
        return true;
    }

    public void flagDuplicateCapture() {
        this.duplicateCapture = true;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getPaymentOrderId() {
        return paymentOrderId;
    }

    public String getProviderPaymentId() {
        return providerPaymentId;
    }

    public String getMethod() {
        return method;
    }

    public AttemptStatus getStatus() {
        return status;
    }

    public Money amount() {
        return new Money(amount, currency);
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorDescription() {
        return errorDescription;
    }

    public boolean isDuplicateCapture() {
        return duplicateCapture;
    }
}
