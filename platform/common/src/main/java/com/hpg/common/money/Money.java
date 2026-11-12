package com.hpg.common.money;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable monetary amount.
 *
 * <p>Why a value object instead of a raw {@link BigDecimal}:
 * <ul>
 *   <li>The amount and its currency always travel together, so INR can never be added to USD.</li>
 *   <li>Scale is normalised to 2 once, here. {@code new BigDecimal("10.0").equals(new BigDecimal("10.00"))}
 *       is {@code false} in Java; normalising makes {@link #equals} behave the way people expect.</li>
 *   <li>A value with more than 2 decimals is rejected instead of being silently rounded — rounding money
 *       is a business decision, never an accident.</li>
 * </ul>
 *
 * <p>Serialised as {@code {"amount": "20000.00", "currency": "INR"}}: the amount is a JSON string so
 * no client parses it as a binary floating point number.
 */
public record Money(
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String currency) implements Comparable<Money> {

    public static final int SCALE = 2;
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    @JsonCreator
    public Money(@JsonProperty("amount") BigDecimal amount, @JsonProperty("currency") String currency) {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (!CURRENCY.matcher(currency).matches()) {
            throw new IllegalArgumentException("currency must be an ISO-4217 code, got: " + currency);
        }
        if (amount.stripTrailingZeros().scale() > SCALE) {
            throw new IllegalArgumentException("amount must have at most " + SCALE + " decimal places");
        }
        this.amount = amount.setScale(SCALE, RoundingMode.UNNECESSARY);
        this.currency = currency;
    }

    public static Money of(String amount, String currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public static Money inr(String amount) {
        return of(amount, "INR");
    }

    public static Money zero(String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    @JsonIgnore
    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other must not be null");
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency;
    }
}
