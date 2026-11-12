package com.hpg.payment.api.dto;

import com.hpg.common.masking.Masking;
import com.hpg.common.money.Money;
import com.hpg.payment.application.PaymentQueryService;
import com.hpg.payment.domain.CaptureMode;
import com.hpg.payment.domain.Payment;
import com.hpg.payment.domain.PaymentAttempt;
import com.hpg.payment.domain.PaymentStatus;
import com.hpg.payment.domain.PaymentStatusHistory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payment as returned to the hospital. Patient contact details are masked: the hospital already has
 * them, and responses end up in logs and support tickets.
 */
public record PaymentResponse(
        UUID id,
        String merchantReference,
        PaymentStatus status,
        Money amount,
        CaptureMode captureMode,
        String description,
        String checkoutUrl,
        Instant expiresAt,
        Instant authorizedAt,
        Instant capturedAt,
        Boolean lateCapture,
        String failureCode,
        String failureReason,
        Customer customer,
        Instant createdAt,
        Instant updatedAt,
        List<Attempt> attempts,
        List<StatusChange> statusHistory) {

    public record Customer(String name, String email, String phone) {
    }

    public record Attempt(String providerPaymentId, String method, String status, String errorCode,
            String errorDescription, boolean duplicateCapture, Instant createdAt) {
    }

    public record StatusChange(PaymentStatus from, PaymentStatus to, String source, String reason, Instant at) {
    }

    public static PaymentResponse summary(Payment p, String checkoutBaseUrl, Instant now) {
        return of(p, checkoutBaseUrl, now, null, null);
    }

    public static PaymentResponse details(PaymentQueryService.PaymentDetails d, String checkoutBaseUrl, Instant now) {
        return of(d.payment(), checkoutBaseUrl, now,
                d.attempts().stream().map(PaymentResponse::attempt).toList(),
                d.history().stream().map(h -> new StatusChange(h.getFromStatus(), h.getToStatus(), h.getSource().name(),
                        h.getReason(), h.getCreatedAt())).toList());
    }

    private static PaymentResponse of(Payment p, String checkoutBaseUrl, Instant now, List<Attempt> attempts,
            List<StatusChange> history) {
        Payment.Customer c = p.customer();
        return new PaymentResponse(p.getId(), p.getMerchantReference(), p.getStatus(), p.amount(), p.getCaptureMode(),
                p.getDescription(),
                p.isPayableAt(now) ? checkoutBaseUrl + "/" + p.getCheckoutToken() : null,
                p.getExpiresAt(), p.getAuthorizedAt(), p.getCapturedAt(), p.isLateCapture() ? Boolean.TRUE : null,
                p.getFailureCode(), p.getFailureReason(),
                c == null ? null : new Customer(c.name(), Masking.email(c.email()), Masking.phone(c.phone())),
                p.getCreatedAt(), p.getUpdatedAt(), attempts, history);
    }

    private static Attempt attempt(PaymentAttempt a) {
        return new Attempt(a.getProviderPaymentId(), a.getMethod(), a.getStatus().name(), a.getErrorCode(),
                a.getErrorDescription(), a.isDuplicateCapture(), a.getCreatedAt());
    }
}
