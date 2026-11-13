package com.hpg.console.client.view;

/** Money as the APIs send it: an exact decimal string plus a currency, never a floating point number. */
public record MoneyView(String amount, String currency) {

    public String formatted() {
        return currency + " " + amount;
    }
}
