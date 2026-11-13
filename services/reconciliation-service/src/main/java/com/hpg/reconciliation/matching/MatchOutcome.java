package com.hpg.reconciliation.matching;

import com.hpg.reconciliation.domain.MatchStatus;

/**
 * Result for one item. {@code provider} is null for items found only internally; {@code internal} is null
 * for items found only at the provider or unreadable.
 */
public record MatchOutcome(MatchStatus status, SettlementRow provider, InternalTransaction internal, String detail) {
}
