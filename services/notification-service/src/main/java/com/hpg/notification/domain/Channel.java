package com.hpg.notification.domain;

/** How a notification is delivered. */
public enum Channel {
    /** Server-to-server callback to the hospital's system, signed with the merchant's secret. */
    WEBHOOK,
    EMAIL,
    SMS
}
