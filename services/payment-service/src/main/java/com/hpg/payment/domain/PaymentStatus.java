package com.hpg.payment.domain;

import java.util.EnumSet;
import java.util.Set;

/** Payment states. Meanings: docs/02-payment-lifecycle.md §3.1. */
public enum PaymentStatus {
    CREATED,
    PENDING,
    PROCESSING,
    AUTHORIZED,
    CAPTURED,
    FAILED,
    CANCELLED,
    EXPIRED,
    UNKNOWN;

    /** States in which no further merchant or timer action applies. (Provider evidence may still override some.) */
    public static final Set<PaymentStatus> TERMINAL = EnumSet.of(CAPTURED, FAILED, CANCELLED, EXPIRED);

    /** States a patient can still pay in. */
    public static final Set<PaymentStatus> PAYABLE = EnumSet.of(CREATED, PENDING);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
