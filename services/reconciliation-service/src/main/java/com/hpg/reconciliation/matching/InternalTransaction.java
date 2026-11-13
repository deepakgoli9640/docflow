package com.hpg.reconciliation.matching;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A ledger booking as reconciliation knows it (from TransactionCreated). */
public record InternalTransaction(
        String transactionRef,
        UUID merchantId,
        String transactionType,
        String providerReference,
        BigDecimal amount,
        String currency,
        Instant postedAt) {

    /** The provider's word for this kind of money movement. */
    public String providerEntityType() {
        return "REFUND".equals(transactionType) ? "refund" : "payment";
    }
}
