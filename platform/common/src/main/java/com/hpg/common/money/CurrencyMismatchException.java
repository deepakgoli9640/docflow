package com.hpg.common.money;

public class CurrencyMismatchException extends IllegalArgumentException {

    public CurrencyMismatchException(String left, String right) {
        super("Currency mismatch: " + left + " vs " + right);
    }
}
