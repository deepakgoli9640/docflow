package com.hpg.payment.provider.razorpay;

import com.hpg.common.money.Money;

import java.math.BigDecimal;

/**
 * Razorpay expresses amounts as integers in the smallest currency unit (paise for INR). This is the
 * only place where that conversion happens; everything else in the system uses {@link Money}.
 */
final class RazorpayAmounts {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private RazorpayAmounts() {
    }

    static long toMinorUnits(Money money) {
        // longValueExact: fails loudly instead of truncating if a value ever had more than 2 decimals.
        return money.amount().multiply(HUNDRED).longValueExact();
    }

    static Money fromMinorUnits(long minorUnits, String currency) {
        return new Money(BigDecimal.valueOf(minorUnits).movePointLeft(2), currency);
    }
}
