package com.hpg.payment.domain;

/** Status of one patient attempt (one provider payment id). Forward-only, except FAILED is final. */
public enum AttemptStatus {
    CREATED(0),
    AUTHORIZED(1),
    CAPTURED(2),
    FAILED(3);

    private final int rank;

    AttemptStatus(int rank) {
        this.rank = rank;
    }

    /** Whether moving from this status to {@code next} is forward progress. */
    public boolean canMoveTo(AttemptStatus next) {
        if (this == next || this == FAILED || this == CAPTURED) {
            return false;
        }
        return next.rank > rank;
    }
}
