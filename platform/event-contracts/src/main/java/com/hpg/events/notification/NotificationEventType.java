package com.hpg.events.notification;

/** Event types on {@code notification.events.v1}. */
public enum NotificationEventType {

    /**
     * A notification could not be delivered and will not be retried again. Published so the failure can
     * be alerted on and shown to operators; there is no automatic consumer, because the fix is always a
     * human one (a hospital repairs its endpoint, someone corrects an address).
     */
    NotificationFailed;

    public static final int CURRENT_VERSION = 1;
}
