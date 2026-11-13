package com.hpg.refund.api.dto;

import com.hpg.common.money.Money;
import com.hpg.refund.domain.Refund;
import com.hpg.refund.domain.RefundStatusHistory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RefundResponse(
        UUID id,
        UUID paymentId,
        Money amount,
        String status,
        String reason,
        String initiatedBy,
        String providerRefundId,
        String failureCode,
        String failureReason,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        List<StatusChange> statusHistory) {

    public record StatusChange(String from, String to, String reason, Instant at) {
    }

    public static RefundResponse summary(Refund r) {
        return of(r, null);
    }

    public static RefundResponse details(Refund r, List<RefundStatusHistory> history) {
        return of(r, history.stream().map(h -> new StatusChange(h.getFromStatus() == null ? null : h.getFromStatus().name(),
                h.getToStatus().name(), h.getReason(), h.getCreatedAt())).toList());
    }

    private static RefundResponse of(Refund r, List<StatusChange> history) {
        return new RefundResponse(r.getId(), r.getPaymentId(), r.amount(), r.getStatus().name(), r.getReason(),
                r.getInitiatedBy().name(), r.getProviderRefundId(), r.getFailureCode(), r.getFailureReason(),
                r.getCreatedAt(), r.getUpdatedAt(), r.getCompletedAt(), history);
    }
}
