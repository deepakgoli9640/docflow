package com.hpg.merchant.domain;

/** See docs/02-payment-lifecycle.md §2: AUTO captures at authorization, MANUAL waits for the hospital. */
public enum CaptureMode {
    AUTO,
    MANUAL
}
