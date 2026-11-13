package com.hpg.reconciliation.api.dto;

import com.hpg.common.money.Money;
import com.hpg.reconciliation.domain.ReconciliationRecord;

import java.time.Instant;
import java.util.UUID;

public record RecordResponse(
        UUID id,
        UUID batchId,
        String status,
        String entityType,
        String providerReference,
        String internalTransactionRef,
        UUID merchantId,
        Money internalAmount,
        Money providerAmount,
        String detail,
        String resolutionStatus,
        String resolutionType,
        String resolutionNote,
        String correctionReference,
        String resolvedBy,
        Instant resolvedAt) {

    public static RecordResponse from(ReconciliationRecord r) {
        return new RecordResponse(r.getId(), r.getBatchId(), r.getStatus().name(), r.getEntityType(), r.getProviderReference(),
                r.getInternalTransactionRef(), r.getMerchantId(), money(r.getInternalAmount(), r.getCurrency()),
                money(r.getProviderAmount(), r.getCurrency()), r.getDetail(),
                r.getResolutionStatus() == null ? null : r.getResolutionStatus().name(),
                r.getResolutionType() == null ? null : r.getResolutionType().name(), r.getResolutionNote(),
                r.getCorrectionReference(), r.getResolvedBy(), r.getResolvedAt());
    }

    private static Money money(java.math.BigDecimal amount, String currency) {
        return amount == null || currency == null ? null : new Money(amount, currency);
    }
}
