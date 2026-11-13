package com.hpg.console.client.view;

import java.time.Instant;
import java.util.List;

public record NotificationDetailsView(NotificationView notification, List<AttemptView> attempts) {

    public record AttemptView(int attemptNo, String outcome, Integer responseCode, String error, long durationMs,
            Instant attemptedAt) {
    }
}
