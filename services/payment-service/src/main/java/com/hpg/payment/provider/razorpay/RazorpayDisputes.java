package com.hpg.payment.provider.razorpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.hpg.payment.provider.ProviderDispute;

import java.time.Instant;
import java.util.Locale;

/**
 * Razorpay's dispute entity (webhooks {@code payment.dispute.*} and the Disputes API).
 * Verify field names against the sandbox: id, payment_id, amount, amount_deducted, currency, reason_code,
 * phase, status (open, under_review, won, lost, closed), respond_by, created_at (amounts in paise).
 */
public final class RazorpayDisputes {

    private RazorpayDisputes() {
    }

    public static boolean isDisputeEvent(String event) {
        return event != null && event.startsWith("payment.dispute.");
    }

    public static ProviderDispute toDispute(JsonNode d) {
        String currency = d.path("currency").asText("INR");
        return new ProviderDispute(
                d.path("id").asText(),
                d.path("payment_id").asText(),
                RazorpayAmounts.fromMinorUnits(d.path("amount").asLong(), currency),
                RazorpayAmounts.fromMinorUnits(d.path("amount_deducted").asLong(0), currency),
                d.path("status").asText("open").toUpperCase(Locale.ROOT),
                d.path("reason_code").asText(null),
                d.path("phase").asText(null),
                d.hasNonNull("respond_by") ? Instant.ofEpochSecond(d.get("respond_by").asLong()) : null,
                d.hasNonNull("created_at") ? Instant.ofEpochSecond(d.get("created_at").asLong()) : null);
    }
}
