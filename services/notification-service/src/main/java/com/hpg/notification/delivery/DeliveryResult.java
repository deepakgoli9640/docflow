package com.hpg.notification.delivery;

import com.hpg.notification.domain.DeliveryOutcome;

/**
 * @param responseCode HTTP status for a webhook, {@code null} for channels that have none
 */
public record DeliveryResult(DeliveryOutcome outcome, Integer responseCode, String error) {

    public static DeliveryResult delivered(Integer responseCode) {
        return new DeliveryResult(DeliveryOutcome.DELIVERED, responseCode, null);
    }

    public static DeliveryResult retryable(String error, Integer responseCode) {
        return new DeliveryResult(DeliveryOutcome.RETRYABLE_FAILURE, responseCode, error);
    }

    public static DeliveryResult permanent(String error, Integer responseCode) {
        return new DeliveryResult(DeliveryOutcome.PERMANENT_FAILURE, responseCode, error);
    }
}
