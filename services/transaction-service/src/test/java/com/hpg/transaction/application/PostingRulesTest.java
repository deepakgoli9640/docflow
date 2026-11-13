package com.hpg.transaction.application;

import com.hpg.common.money.Money;
import com.hpg.transaction.domain.LedgerAccounts;
import com.hpg.transaction.domain.LedgerEntry;
import com.hpg.transaction.domain.PostingRules;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostingRulesTest {

    final UUID merchant = UUID.fromString("b1e7c3d2-0000-7000-8000-000000000001");

    @Test
    void captureDebitsClearingAndCreditsTheHospital() {
        List<LedgerEntry> entries = PostingRules.capture(merchant, Money.inr("20000.00"));
        assertThat(entries).containsExactly(
                LedgerEntry.debit(LedgerAccounts.PROVIDER_CLEARING, Money.inr("20000.00")),
                LedgerEntry.credit("MERCHANT_PAYABLE:" + merchant, Money.inr("20000.00")));
        PostingRules.requireBalanced(entries);
    }

    @Test
    void refundIsTheMirrorOfACapture() {
        List<LedgerEntry> entries = PostingRules.refund(merchant, Money.inr("5000.00"));
        assertThat(entries).containsExactly(
                LedgerEntry.debit("MERCHANT_PAYABLE:" + merchant, Money.inr("5000.00")),
                LedgerEntry.credit(LedgerAccounts.PROVIDER_CLEARING, Money.inr("5000.00")));
        PostingRules.requireBalanced(entries);
    }

    @Test
    void rejectsUnbalancedMixedCurrencyAndSingleEntryPostings() {
        assertThatThrownBy(() -> PostingRules.requireBalanced(List.of(
                LedgerEntry.debit("A", Money.inr("10.00")), LedgerEntry.credit("B", Money.inr("9.99")))))
                .hasMessageContaining("unbalanced");
        assertThatThrownBy(() -> PostingRules.requireBalanced(List.of(
                LedgerEntry.debit("A", Money.inr("10.00")), LedgerEntry.credit("B", Money.of("10.00", "USD")))))
                .hasMessageContaining("one currency");
        assertThatThrownBy(() -> PostingRules.requireBalanced(List.of(LedgerEntry.debit("A", Money.inr("1.00")))))
                .hasMessageContaining("two entries");
    }

    @Test
    void accountCodesAreValidated() {
        assertThat(LedgerAccounts.isValid("PROVIDER_CLEARING")).isTrue();
        assertThat(LedgerAccounts.isValid("MERCHANT_PAYABLE:" + merchant)).isTrue();
        assertThat(LedgerAccounts.isValid("MERCHANT_PAYABLE:x' OR 1=1")).isFalse();
        assertThat(LedgerAccounts.isCreditNormal("MERCHANT_PAYABLE:" + merchant)).isTrue();
        assertThat(LedgerAccounts.isCreditNormal("PROVIDER_CLEARING")).isFalse();
    }

    @Test
    void settlementMovesTheNetToTheBankAndFeesToExpense() {
        List<LedgerEntry> entries = PostingRules.settlement(Money.inr("15000.00"), Money.inr("300.00"), Money.inr("54.00"));
        PostingRules.requireBalanced(entries);
        assertThat(entries).extracting(e -> e.accountCode() + " " + e.direction() + " " + e.amount().amount())
                .containsExactly("SETTLEMENT_BANK DEBIT 14646.00", "PROVIDER_FEES DEBIT 354.00",
                        "PROVIDER_CLEARING CREDIT 15000.00");
        assertThat(PostingRules.settlement(Money.inr("100.00"), Money.inr("0.00"), Money.inr("0.00"))).hasSize(2);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                PostingRules.settlement(Money.inr("10.00"), Money.inr("10.00"), Money.inr("0.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void chargebackAndItsReversalBalanceAndCancelOut() {
        List<LedgerEntry> cb = PostingRules.chargeback(merchant, Money.inr("700.00"));
        List<LedgerEntry> back = PostingRules.chargebackReversal(merchant, Money.inr("700.00"));
        PostingRules.requireBalanced(cb);
        PostingRules.requireBalanced(back);
        assertThat(cb.get(0).accountCode()).isEqualTo(back.get(1).accountCode());
        assertThat(cb.get(0).direction()).isNotEqualTo(back.get(1).direction());
    }
}
