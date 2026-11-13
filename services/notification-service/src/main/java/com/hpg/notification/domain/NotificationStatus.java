package com.hpg.notification.domain;

public enum NotificationStatus {
    /** Waiting for its (next) delivery attempt. */
    PENDING,
    DELIVERED,
    /** Nothing to deliver to (e.g. the hospital has no webhook URL configured). */
    SKIPPED,
    /** Retry budget exhausted; NotificationFailed was published. OPS can re-queue it. */
    DEAD
}
