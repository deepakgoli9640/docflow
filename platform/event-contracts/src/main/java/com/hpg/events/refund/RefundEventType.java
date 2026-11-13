package com.hpg.events.refund;

/** Event types published on {@code refund.events.v1} (keyed by paymentId, docs/06-kafka-events.md §2). */
public enum RefundEventType {
    RefundInitiated,
    RefundCompleted,
    RefundFailed;

    public static final int CURRENT_VERSION = 1;
}
