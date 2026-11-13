package com.hpg.notification.api.dto;

import com.hpg.notification.application.NotificationAdminService;
import com.hpg.notification.domain.DeliveryOutcome;

import java.time.Instant;
import java.util.List;

public record NotificationDetailsResponse(NotificationResponse notification, List<Attempt> attempts) {

    public record Attempt(int attemptNo, DeliveryOutcome outcome, Integer responseCode, String error,
            long durationMs, Instant attemptedAt) {
    }

    public static NotificationDetailsResponse from(NotificationAdminService.Details details) {
        return new NotificationDetailsResponse(NotificationResponse.from(details.notification()),
                details.attempts().stream()
                        .map(a -> new Attempt(a.getAttemptNo(), a.getOutcome(), a.getResponseCode(), a.getError(),
                                a.getDurationMs(), a.getAttemptedAt()))
                        .toList());
    }
}
