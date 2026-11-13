package com.hpg.reconciliation.matching;

import com.hpg.reconciliation.domain.MatchStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Matches a provider settlement report against internal ledger transactions (docs/05-reconciliation.md §4).
 * Pure Java: no I/O, no Spring, so every rule is covered by table-driven unit tests.
 *
 * <p>Match key: the provider reference ({@code pay_...} / {@code rfnd_...}). Gross amounts are compared with
 * {@code compareTo} (scale-insensitive); provider fees are reported, never a mismatch.
 */
public final class MatchingEngine {

    private static final Set<String> KNOWN_TYPES = Set.of("payment", "refund");

    /**
     * @param providerRows       the report, including unreadable rows
     * @param internalByRef      internal transactions for every reference that appears in the report
     *                           (more than one per reference means a duplicate on our side)
     * @param dueUnsettled       internal transactions old enough that the provider should have settled them
     *                           and not yet matched or resolved in any batch
     * @param settledInOtherDays references already matched in a report for a different business date
     */
    public record Input(List<SettlementRow> providerRows, Map<String, List<InternalTransaction>> internalByRef,
            Collection<InternalTransaction> dueUnsettled, Set<String> settledInOtherDays) {
    }

    public List<MatchOutcome> match(Input in) {
        List<MatchOutcome> out = new ArrayList<>();
        Map<String, List<SettlementRow>> byRef = new LinkedHashMap<>();
        for (SettlementRow row : in.providerRows()) {
            if (!row.isValid()) {
                out.add(new MatchOutcome(MatchStatus.PENDING_REVIEW, row, null,
                        "Row " + row.rowNumber() + " could not be read: " + row.parseError()));
            } else {
                byRef.computeIfAbsent(row.entityId(), k -> new ArrayList<>()).add(row);
            }
        }
        Set<String> seenRefs = new HashSet<>(byRef.keySet());

        for (Map.Entry<String, List<SettlementRow>> e : byRef.entrySet()) {
            String ref = e.getKey();
            List<SettlementRow> rows = e.getValue();
            List<InternalTransaction> internal = in.internalByRef().getOrDefault(ref, List.of());
            InternalTransaction single = internal.size() == 1 ? internal.getFirst() : null;
            if (rows.size() > 1) {
                for (SettlementRow row : rows) {
                    out.add(new MatchOutcome(MatchStatus.DUPLICATE, row, single,
                            ref + " appears " + rows.size() + " times in the provider report"));
                }
                continue;
            }
            out.add(matchOne(rows.getFirst(), internal, in.settledInOtherDays().contains(ref)));
        }

        for (InternalTransaction t : in.dueUnsettled()) {
            if (t.providerReference() == null || !seenRefs.contains(t.providerReference())) {
                out.add(new MatchOutcome(MatchStatus.MISSING_IN_PROVIDER, null, t,
                        t.providerReference() == null
                                ? "Booked internally without a provider reference; cannot be matched"
                                : "Not in any provider report although booked on " + t.postedAt()));
            }
        }
        return out;
    }

    private static MatchOutcome matchOne(SettlementRow row, List<InternalTransaction> internal, boolean settledBefore) {
        String ref = row.entityId();
        if (!KNOWN_TYPES.contains(row.entityType())) {
            return new MatchOutcome(MatchStatus.PENDING_REVIEW, row, internal.size() == 1 ? internal.getFirst() : null,
                    "Provider entry type '" + row.entityType() + "' needs manual review");
        }
        if (settledBefore) {
            return new MatchOutcome(MatchStatus.DUPLICATE, row, internal.size() == 1 ? internal.getFirst() : null,
                    ref + " was already settled in a report for another day");
        }
        if (internal.isEmpty()) {
            return new MatchOutcome(MatchStatus.MISSING_IN_INTERNAL_SYSTEM, row, null,
                    "The provider settled " + ref + " but our ledger has no such " + row.entityType());
        }
        if (internal.size() > 1) {
            return new MatchOutcome(MatchStatus.DUPLICATE, row, null,
                    "Our ledger has " + internal.size() + " transactions for " + ref);
        }
        InternalTransaction t = internal.getFirst();
        if (!t.providerEntityType().equals(row.entityType())) {
            return new MatchOutcome(MatchStatus.PENDING_REVIEW, row, t, "Provider says " + row.entityType()
                    + ", our ledger says " + t.providerEntityType().toLowerCase(java.util.Locale.ROOT));
        }
        if (!t.currency().equals(row.currency())) {
            return new MatchOutcome(MatchStatus.MISMATCHED, row, t,
                    "Currency differs: provider " + row.currency() + ", ledger " + t.currency());
        }
        if (t.amount().compareTo(row.amount()) != 0) {
            return new MatchOutcome(MatchStatus.MISMATCHED, row, t, "Amount differs: provider "
                    + row.amount().toPlainString() + ", ledger " + t.amount().toPlainString() + " " + t.currency());
        }
        return new MatchOutcome(MatchStatus.MATCHED, row, t, null);
    }
}
