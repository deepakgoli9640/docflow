package com.hpg.notification.domain;

/**
 * What one delivery attempt achieved.
 *
 * <p>The distinction that matters is <b>retryable</b> versus <b>permanent</b>. A hospital endpoint that
 * is down, slow, or answering 500 is retryable: it will probably work later. A webhook URL that resolves
 * to a private address, or an address the mail server rejects outright, will never work — retrying it for
 * a day would only fill the logs and delay the alert that someone has to fix the configuration.
 */
public enum DeliveryOutcome {
    DELIVERED,
    RETRYABLE_FAILURE,
    PERMANENT_FAILURE
}
