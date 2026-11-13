package com.hpg.console.client.view;

import java.time.Instant;
import java.util.List;

public record PaymentView(String id, String merchantReference, String status, MoneyView amount, String captureMode,
        String description, String checkoutUrl, Instant expiresAt, Instant authorizedAt, Instant capturedAt,
        Boolean lateCapture, String failureCode, String failureReason, CustomerView customer, Instant createdAt,
        Instant updatedAt, List<AttemptView> attempts, List<StatusChangeView> statusHistory) {

    public record CustomerView(String name, String email, String phone) {
    }

    public record AttemptView(String providerPaymentId, String method, String status, String errorCode,
            String errorDescription, boolean duplicateCapture, Instant createdAt) {
    }

    public record StatusChangeView(String from, String to, String source, String reason, Instant at) {
    }

    /** Whether the page should keep refreshing itself: the payment can still change on its own. */
    public boolean inFlight() {
        return List.of("CREATED", "PENDING", "PROCESSING", "UNKNOWN").contains(status);
    }

    public boolean capturable() {
        return "AUTHORIZED".equals(status);
    }

    public boolean cancellable() {
        return List.of("CREATED", "PENDING", "AUTHORIZED").contains(status);
    }
}
