package com.hpg.web.dto;

import com.hpg.common.money.Money;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Money as accepted in request bodies: {@code {"amount": "20000.00", "currency": "INR"}}.
 *
 * <p>Separate from {@link Money} so that bad input produces field-level {@code VALIDATION_FAILED}
 * errors instead of a generic "malformed body" from a constructor exception.
 */
public record MoneyRequest(
        @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @NotNull @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO-4217 currency code") String currency) {

    public Money toMoney() {
        return new Money(amount, currency);
    }
}
