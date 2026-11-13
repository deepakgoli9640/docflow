package com.hpg.events;

/**
 * Kafka headers carried alongside every event, so a consumer can filter or route without deserialising
 * the body, and so tooling can see what a message is.
 *
 * <p>Headers are a convenience copy: the envelope inside the body stays the source of truth, because a
 * header is not covered by anything that validates the event.
 */
public final class EventHeaders {

    public static final String EVENT_ID = "hpg-event-id";
    public static final String EVENT_TYPE = "hpg-event-type";
    public static final String AGGREGATE_TYPE = "hpg-aggregate-type";

    private EventHeaders() {
    }
}
