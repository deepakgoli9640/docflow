package com.hpg.transaction.domain;

import com.hpg.common.money.Money;

/** One side of a posting. */
public record LedgerEntry(String accountCode, Direction direction, Money amount) {

    public enum Direction { DEBIT, CREDIT }

    public static LedgerEntry debit(String account, Money amount) {
        return new LedgerEntry(account, Direction.DEBIT, amount);
    }

    public static LedgerEntry credit(String account, Money amount) {
        return new LedgerEntry(account, Direction.CREDIT, amount);
    }
}
