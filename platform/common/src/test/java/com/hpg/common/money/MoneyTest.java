package com.hpg.common.money;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void normalisesScaleSoEqualsWorksAcrossRepresentations() {
        assertThat(Money.inr("10")).isEqualTo(Money.inr("10.00"));
        assertThat(Money.inr("10.5").amount()).isEqualByComparingTo("10.50");
        assertThat(Money.inr("10.5").amount().scale()).isEqualTo(2);
    }

    @Test
    void rejectsMoreThanTwoDecimalsInsteadOfRounding() {
        assertThatThrownBy(() -> Money.inr("10.005")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsTrailingZerosBeyondScale() {
        assertThat(Money.inr("10.000")).isEqualTo(Money.inr("10.00"));
    }

    @Test
    void rejectsInvalidCurrency() {
        assertThatThrownBy(() -> Money.of("1.00", "inr")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of("1.00", "RUPEE")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void arithmeticAndComparison() {
        Money a = Money.inr("20000.00");
        Money b = Money.inr("5000.50");
        assertThat(a.minus(b)).isEqualTo(Money.inr("14999.50"));
        assertThat(a.plus(b)).isEqualTo(Money.inr("25000.50"));
        assertThat(a.isGreaterThan(b)).isTrue();
        assertThat(b.isLessThan(a)).isTrue();
        assertThat(Money.zero("INR").isPositive()).isFalse();
    }

    @Test
    void refusesToMixCurrencies() {
        assertThatThrownBy(() -> Money.inr("1.00").plus(Money.of("1.00", "USD")))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void serialisesAmountAsStringAndRoundTrips() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(Money.inr("20000"));
        assertThat(json).isEqualTo("{\"amount\":\"20000.00\",\"currency\":\"INR\"}");
        assertThat(mapper.readValue(json, Money.class)).isEqualTo(Money.inr("20000.00"));
    }

    @Test
    void neverUsesBinaryFloatingPoint() {
        // 0.1 + 0.2 != 0.3 in double arithmetic; Money must be exact.
        assertThat(Money.inr("0.10").plus(Money.inr("0.20"))).isEqualTo(new Money(new BigDecimal("0.30"), "INR"));
    }
}
