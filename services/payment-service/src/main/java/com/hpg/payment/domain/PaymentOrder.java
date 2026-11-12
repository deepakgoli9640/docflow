package com.hpg.payment.domain;

import com.hpg.common.id.Ids;
import com.hpg.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** The order created at the provider for a payment. Immutable once recorded. */
@Entity
@Immutable
@Table(name = "payment_orders")
public class PaymentOrder {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "provider", nullable = false, length = 20)
    private String provider;

    @Column(name = "provider_order_id", nullable = false, length = 64)
    private String providerOrderId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PaymentOrder() {
        // for JPA
    }

    public PaymentOrder(UUID paymentId, String provider, String providerOrderId, Money amount, Instant createdAt) {
        this.id = Ids.newId();
        this.paymentId = paymentId;
        this.provider = provider;
        this.providerOrderId = providerOrderId;
        this.amount = amount.amount();
        this.currency = amount.currency();
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderOrderId() {
        return providerOrderId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
