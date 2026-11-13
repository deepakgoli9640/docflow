package com.hpg.events;

/** Kafka topics: one per aggregate, keyed by aggregate id (docs/06-kafka-events.md §2). */
public final class Topics {

    public static final String PAYMENT_EVENTS = "payment.events.v1";
    public static final String REFUND_EVENTS = "refund.events.v1";
    public static final String LEDGER_EVENTS = "ledger.events.v1";
    public static final String MERCHANT_SNAPSHOTS = "merchant.snapshots.v1";
    public static final String RECONCILIATION_EVENTS = "reconciliation.events.v1";
    public static final String NOTIFICATION_EVENTS = "notification.events.v1";

    /** Suffix of the dead-letter topic for a source topic (records that could not be processed). */
    public static final String DLT_SUFFIX = ".dlt";

    private Topics() {
    }

    public static String dlt(String topic) {
        return topic + DLT_SUFFIX;
    }
}
