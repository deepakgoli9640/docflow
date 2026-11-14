package com.hpg.events.reconciliation;

import com.hpg.common.money.Money;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code SettlementConfirmed} on {@code reconciliation.events.v1} (key = transactionRef): a captured payment
 * appeared in the provider's settlement report with the same amount (MATCHED). transaction-service books the
 * settlement: the net into {@code SETTLEMENT_BANK}, fee and tax into {@code PROVIDER_FEES}, out of
 * {@code PROVIDER_CLEARING} (docs/04-ledger.md §2.2).
 *
 * @param gross    the settled payment amount
 * @param fee      the provider's fee for it (zero if the report has none)
 * @param tax      tax on the fee (zero if none)
 */
public record SettlementConfirmedPayload(
        UUID batchId,
        UUID merchantId,
        String transactionRef,
        String providerReference,
        Money gross,
        Money fee,
        Money tax,
        String settlementId,
        Instant settledAt) {

    public static final String EVENT_TYPE = "SettlementConfirmed";
    public static final int CURRENT_VERSION = 1;
}
