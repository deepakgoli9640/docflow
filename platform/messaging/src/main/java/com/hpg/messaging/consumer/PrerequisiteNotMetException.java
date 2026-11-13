package com.hpg.messaging.consumer;

/**
 * The event arrived before something it depends on (e.g. a refund before its payment's capture, which
 * travels on another topic). Retried in place with backoff, which keeps the partition's order, and
 * dead-lettered only after the retry budget (docs/04-ledger.md §5).
 */
public class PrerequisiteNotMetException extends RuntimeException {

    public PrerequisiteNotMetException(String message) {
        super(message);
    }
}
