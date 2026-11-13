package com.hpg.messaging.consumer;

/**
 * Thrown by a consumer when an event can never be processed successfully — it is wrong, not unlucky
 * (unknown event version, impossible payload). The container sends it straight to the dead-letter topic
 * instead of retrying, because retrying cannot help and would hold up the partition.
 */
public class NonRetryableEventException extends RuntimeException {

    public NonRetryableEventException(String message) {
        super(message);
    }

    public NonRetryableEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
