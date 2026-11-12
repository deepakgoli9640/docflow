package com.hpg.payment.provider.resilience;

import com.hpg.payment.provider.PaymentProvider;

/**
 * The provider, exposed through two lanes with separate bulkheads:
 * {@link #live()} for requests a patient or hospital is waiting on, {@link #recovery()} for background
 * verification jobs.
 */
public record PaymentProviders(PaymentProvider live, PaymentProvider recovery) {
}
