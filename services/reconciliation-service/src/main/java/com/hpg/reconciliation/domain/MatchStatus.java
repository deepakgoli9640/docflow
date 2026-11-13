package com.hpg.reconciliation.domain;

/** Outcome of matching one item (docs/05-reconciliation.md §4). */
public enum MatchStatus {
    MATCHED, MISMATCHED, MISSING_IN_PROVIDER, MISSING_IN_INTERNAL_SYSTEM, DUPLICATE, PENDING_REVIEW
}
