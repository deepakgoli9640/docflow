package com.hpg.transaction.api.dto;

import com.hpg.common.money.Money;
import com.hpg.transaction.application.TransactionQueryService;
import com.hpg.transaction.domain.Transaction;
import com.hpg.transaction.infrastructure.persistence.LedgerStore;
import com.hpg.transaction.infrastructure.persistence.TransactionTimeline;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransactionResponse(
        String transactionRef,
        String type,
        String status,
        UUID paymentId,
        UUID refundId,
        String merchantReference,
        Money amount,
        String provider,
        String providerReference,
        String failureCode,
        String failureReason,
        boolean lateCapture,
        Instant initiatedAt,
        Instant completedAt,
        Instant lastEventAt,
        List<TimelineEntry> timeline,
        List<Entry> ledgerEntries) {

    public record TimelineEntry(String eventType, String status, Instant occurredAt) {
    }

    public record Entry(String postingType, String accountCode, String direction, Money amount, Instant postedAt) {
    }

    public static TransactionResponse summary(Transaction t) {
        return of(t, null, null);
    }

    public static TransactionResponse details(TransactionQueryService.Details d) {
        return of(d.transaction(),
                d.events().stream().map((TransactionTimeline.Entry e) -> new TimelineEntry(e.eventType(), e.status(),
                        e.occurredAt())).toList(),
                d.ledgerEntries().stream().map((LedgerStore.StoredEntry e) -> new Entry(e.postingType(),
                        e.accountCode(), e.direction(), e.amount(), e.createdAt())).toList());
    }

    private static TransactionResponse of(Transaction t, List<TimelineEntry> timeline, List<Entry> entries) {
        return new TransactionResponse(t.getTransactionRef(), t.getType().name(), t.getStatus().name(), t.getPaymentId(),
                t.getRefundId(), t.getMerchantReference(), t.amount(), t.getProvider(), t.getProviderReference(),
                t.getFailureCode(), t.getFailureReason(), t.isLateCapture(), t.getInitiatedAt(), t.getCompletedAt(),
                t.getLastEventAt(), timeline, entries);
    }
}
