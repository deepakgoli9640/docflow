package com.hpg.common.error;

/**
 * A stable, machine-readable error identifier that clients can program against.
 *
 * <p>Each service defines its own enum implementing this interface. The HTTP status is part of the
 * code so the mapping from business failure to HTTP lives in one place instead of in every controller.
 */
public interface ErrorCode {

    /** Stable identifier, e.g. {@code REFUND_EXCEEDS_CAPTURED_AMOUNT}. Never change once published. */
    String code();

    /** HTTP status to return for this error. */
    int httpStatus();

    /** Short human-readable summary (the Problem Details {@code title}). */
    String title();
}
