package com.hpg.payment.application;

/** What applying a piece of provider evidence did. Recorded on webhook inbox rows. */
public enum EvidenceOutcome {
    APPLIED,
    NO_CHANGE,
    STALE_IGNORED,
    UNKNOWN_ORDER
}
