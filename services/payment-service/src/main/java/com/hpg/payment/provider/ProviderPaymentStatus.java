package com.hpg.payment.provider;

/** Provider-neutral status of one payment attempt. */
public enum ProviderPaymentStatus {
    CREATED,
    AUTHORIZED,
    CAPTURED,
    /** Captured and later (partly) refunded: for the payment lifecycle this still means captured. */
    REFUNDED,
    FAILED
}
