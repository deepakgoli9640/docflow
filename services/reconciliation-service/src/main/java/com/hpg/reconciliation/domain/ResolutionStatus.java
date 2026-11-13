package com.hpg.reconciliation.domain;

/** Workflow of a discrepancy, independent of its match status (docs/05-reconciliation.md §5). */
public enum ResolutionStatus {
    OPEN, IN_REVIEW, RESOLVED
}
