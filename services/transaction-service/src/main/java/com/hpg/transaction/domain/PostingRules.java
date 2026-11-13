package com.hpg.transaction.domain;

import com.hpg.common.money.Money;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Which accounts move for which business event (docs/04-ledger.md §2.2). Pure functions; every result
 * balances, which {@link #requireBalanced} checks before anything is written.
 */
public final class PostingRules {

    public enum PostingType { CAPTURE, REFUND, REVERSAL }

    private PostingRules() {
    }

    /** Payment captured: the provider holds the money (asset up), we owe the hospital (liability up). */
    public static List<LedgerEntry> capture(UUID merchantId, Money amount) {
        return List.of(
                LedgerEntry.debit(LedgerAccounts.PROVIDER_CLEARING, amount),
                LedgerEntry.credit(LedgerAccounts.merchantPayable(merchantId), amount));
    }

    /** Refund completed: we owe the hospital less, the provider paid the patient back. */
    public static List<LedgerEntry> refund(UUID merchantId, Money amount) {
        return List.of(
                LedgerEntry.debit(LedgerAccounts.merchantPayable(merchantId), amount),
                LedgerEntry.credit(LedgerAccounts.PROVIDER_CLEARING, amount));
    }

    public static void requireBalanced(List<LedgerEntry> entries) {
        if (entries.size() < 2) {
            throw new IllegalArgumentException("A posting needs at least two entries");
        }
        String currency = entries.getFirst().amount().currency();
        BigDecimal net = BigDecimal.ZERO;
        for (LedgerEntry e : entries) {
            if (!e.amount().currency().equals(currency)) {
                throw new IllegalArgumentException("A posting must use one currency");
            }
            if (!e.amount().isPositive()) {
                throw new IllegalArgumentException("Ledger amounts must be positive");
            }
            net = e.direction() == LedgerEntry.Direction.DEBIT ? net.add(e.amount().amount()) : net.subtract(e.amount().amount());
        }
        if (net.signum() != 0) {
            throw new IllegalArgumentException("Posting is unbalanced by " + net);
        }
    }
}
