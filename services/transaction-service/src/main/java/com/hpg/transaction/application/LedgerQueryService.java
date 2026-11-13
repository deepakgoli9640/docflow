package com.hpg.transaction.application;

import com.hpg.common.error.BusinessException;
import com.hpg.common.money.Money;
import com.hpg.transaction.domain.LedgerAccounts;
import com.hpg.transaction.domain.TransactionErrorCode;
import com.hpg.transaction.infrastructure.persistence.LedgerStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** OPS views of the ledger: account balances and the integrity check (docs/04-ledger.md §6-7). */
@Service
@Transactional(readOnly = true)
public class LedgerQueryService {

    private final LedgerStore ledger;

    public LedgerQueryService(LedgerStore ledger) {
        this.ledger = ledger;
    }

    /** @param balance on the account's normal side (credit-normal for payables, debit-normal otherwise) */
    public record Balance(String accountCode, String currency, Money debits, Money credits, Money balance) {
    }

    public record IntegrityReport(boolean healthy, long unbalancedPostings, boolean debitsEqualCredits,
            long settledTransactionsWithoutPosting, long negativeMerchantPayables) {
    }

    public List<Balance> balance(String accountCode) {
        if (!LedgerAccounts.isValid(accountCode)) {
            throw new BusinessException(TransactionErrorCode.INVALID_ACCOUNT, "Unknown account " + accountCode);
        }
        boolean creditNormal = LedgerAccounts.isCreditNormal(accountCode);
        return ledger.totals(accountCode).stream().map(t -> new Balance(accountCode, t.currency(),
                new Money(t.debits(), t.currency()), new Money(t.credits(), t.currency()),
                new Money(creditNormal ? t.credits().subtract(t.debits()) : t.debits().subtract(t.credits()),
                        t.currency()))).toList();
    }

    public IntegrityReport integrity() {
        long unbalanced = ledger.unbalancedPostings();
        boolean equal = ledger.grandTotals().stream().allMatch(t -> t.debits().compareTo(t.credits()) == 0);
        long missing = ledger.settledTransactionsWithoutPosting();
        long negative = ledger.negativeMerchantPayables();
        return new IntegrityReport(unbalanced == 0 && equal && missing == 0 && negative == 0, unbalanced, equal,
                missing, negative);
    }
}
