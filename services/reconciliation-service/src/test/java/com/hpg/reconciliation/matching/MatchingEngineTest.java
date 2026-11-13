package com.hpg.reconciliation.matching;

import com.hpg.reconciliation.domain.MatchStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MatchingEngineTest {

    final MatchingEngine engine = new MatchingEngine();
    final Instant t0 = Instant.parse("2026-11-10T09:00:00Z");

    SettlementRow row(int n, String ref, String type, String amount, String currency) {
        return new SettlementRow(n, ref, type, null, new BigDecimal(amount), BigDecimal.ZERO, BigDecimal.ZERO, currency,
                "setl_1", t0, t0, ref, null);
    }

    InternalTransaction internal(String ref, String type, String amount, String currency) {
        return new InternalTransaction("TXN-" + ref, UUID.randomUUID(), type, ref, new BigDecimal(amount), currency, t0);
    }

    MatchOutcome single(SettlementRow row, List<InternalTransaction> internal, boolean settledBefore) {
        List<MatchOutcome> out = engine.match(new MatchingEngine.Input(List.of(row),
                internal.isEmpty() ? Map.of() : Map.of(row.entityId(), internal), List.of(),
                settledBefore ? Set.of(row.entityId()) : Set.of()));
        assertThat(out).hasSize(1);
        return out.getFirst();
    }

    /** provider type, provider amount, provider currency, ledger type, ledger amount, ledger currency, expected */
    @ParameterizedTest(name = "{0} {1} {2} vs {3} {4} {5} -> {6}")
    @CsvSource({
            "payment, 20000.00, INR, PAYMENT, 20000.00, INR, MATCHED",
            "payment, 20000.0,  INR, PAYMENT, 20000.00, INR, MATCHED",      // scale differs: still equal
            "refund,  5000.00,  INR, REFUND,  5000.00,  INR, MATCHED",
            "payment, 19999.99, INR, PAYMENT, 20000.00, INR, MISMATCHED",
            "payment, 20000.00, USD, PAYMENT, 20000.00, INR, MISMATCHED",
            "refund,  20000.00, INR, PAYMENT, 20000.00, INR, PENDING_REVIEW", // type differs
            "adjustment, 10.00, INR, PAYMENT, 10.00,    INR, PENDING_REVIEW"})
    void comparesOneProviderRowWithOneLedgerTransaction(String pType, String pAmount, String pCurrency, String lType,
            String lAmount, String lCurrency, MatchStatus expected) {
        MatchOutcome o = single(row(1, "pay_1", pType, pAmount, pCurrency), List.of(internal("pay_1", lType, lAmount, lCurrency)),
                false);
        assertThat(o.status()).isEqualTo(expected);
        assertThat(o.internal()).isNotNull();
        if (expected != MatchStatus.MATCHED) {
            assertThat(o.detail()).isNotBlank();
        }
    }

    @Test
    void providerOnlyIsMissingInternally() {
        MatchOutcome o = single(row(1, "pay_ghost", "payment", "10.00", "INR"), List.of(), false);
        assertThat(o.status()).isEqualTo(MatchStatus.MISSING_IN_INTERNAL_SYSTEM);
        assertThat(o.internal()).isNull();
    }

    @Test
    void twoLedgerTransactionsForOneReferenceAreADuplicate() {
        assertThat(single(row(1, "pay_1", "payment", "10.00", "INR"),
                List.of(internal("pay_1", "PAYMENT", "10.00", "INR"), internal("pay_1", "PAYMENT", "10.00", "INR")), false)
                .status()).isEqualTo(MatchStatus.DUPLICATE);
    }

    @Test
    void settledAgainOnAnotherDayIsADuplicate() {
        assertThat(single(row(1, "pay_1", "payment", "10.00", "INR"), List.of(internal("pay_1", "PAYMENT", "10.00", "INR")), true)
                .status()).isEqualTo(MatchStatus.DUPLICATE);
    }

    @Test
    void sameReferenceTwiceInTheReportMarksEveryCopyDuplicate() {
        List<MatchOutcome> out = engine.match(new MatchingEngine.Input(
                List.of(row(1, "pay_1", "payment", "10.00", "INR"), row(2, "pay_1", "payment", "10.00", "INR")),
                Map.of("pay_1", List.of(internal("pay_1", "PAYMENT", "10.00", "INR"))), List.of(), Set.of()));
        assertThat(out).extracting(MatchOutcome::status).containsExactly(MatchStatus.DUPLICATE, MatchStatus.DUPLICATE);
    }

    @Test
    void unreadableRowsNeedReview() {
        List<MatchOutcome> out = engine.match(new MatchingEngine.Input(
                List.of(SettlementRow.malformed(7, "x,y", "amount is not a number: y")), Map.of(), List.of(), Set.of()));
        assertThat(out).singleElement().satisfies(o -> {
            assertThat(o.status()).isEqualTo(MatchStatus.PENDING_REVIEW);
            assertThat(o.detail()).contains("Row 7").contains("amount is not a number");
        });
    }

    @Test
    void dueInternalTransactionsAbsentFromTheReportAreMissingAtTheProvider() {
        InternalTransaction settled = internal("pay_1", "PAYMENT", "10.00", "INR");
        InternalTransaction missing = internal("pay_2", "PAYMENT", "20.00", "INR");
        List<MatchOutcome> out = engine.match(new MatchingEngine.Input(List.of(row(1, "pay_1", "payment", "10.00", "INR")),
                Map.of("pay_1", List.of(settled)), List.of(settled, missing), Set.of()));
        assertThat(out).extracting(o -> o.status() + ":" + (o.internal() == null ? "-" : o.internal().providerReference()))
                .containsExactlyInAnyOrder("MATCHED:pay_1", "MISSING_IN_PROVIDER:pay_2");
    }
}
