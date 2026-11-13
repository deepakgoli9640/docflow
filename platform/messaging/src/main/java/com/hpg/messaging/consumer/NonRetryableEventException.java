package com.hpg.messaging.consumer;

/**
 * The record can never be processed (malformed, fails validation). Sent straight to the dead-letter topic:
 * retrying a poison pill only blocks the partition.
 */
public class NonRetryableEventException extends RuntimeException {

    public NonRetryableEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
