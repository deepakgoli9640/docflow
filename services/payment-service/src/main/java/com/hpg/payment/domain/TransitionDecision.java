package com.hpg.payment.domain;

public enum TransitionDecision {
    /** Legal: apply it. */
    APPLY,
    /** Already in the target state: nothing to do (idempotent repeat). */
    NO_OP,
    /**
     * Provider evidence that does not move the payment forward (e.g. "authorized" arriving after
     * "captured"): acknowledged and ignored. Out-of-order webhooks end up here.
     */
    IGNORE_STALE,
    /** A merchant or system request that is not allowed in the current state: reject with 409. */
    REJECT
}
