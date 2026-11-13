package com.hpg.notification.domain;

public enum Channel {
    /** Signed HTTP POST to the hospital's webhook URL. */
    WEBHOOK,
    /** Email to the patient. */
    EMAIL
}
