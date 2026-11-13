package com.hpg.refund.domain;

/** Who asked for the refund. Only MERCHANT is used in v1; the others exist so compensation can be added later. */
public enum InitiatedBy {
    MERCHANT,
    OPS,
    SYSTEM
}
