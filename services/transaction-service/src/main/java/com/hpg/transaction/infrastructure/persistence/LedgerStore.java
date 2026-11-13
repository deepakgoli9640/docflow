package com.hpg.transaction.infrastructure.persistence;

import com.hpg.common.money.Money;
import com.hpg.transaction.domain.LedgerEntry;
import com.hpg.transaction.domain.PostingRules;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The ledger tables. Insert and read only: there is deliberately no update or delete here (and database
 * triggers reject them). Corrections are new REVERSAL postings.
 */
@Component
public class LedgerStore {

    private final JdbcTemplate jdbc;

    public LedgerStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record StoredEntry(long id, UUID postingId, String postingType, String accountCode, String direction,
            Money amount, Instant createdAt) {
    }

    public record AccountTotals(String currency, BigDecimal debits, BigDecimal credits) {
    }

    /**
     * Writes one balanced posting. {@code postingId} is the source event id, and (posting, account, direction)
     * is unique, so the same event can never be posted twice.
     *
     * @return true if written; false if this posting already exists
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean post(UUID postingId, UUID transactionId, PostingRules.PostingType type, List<LedgerEntry> entries,
            Instant now) {
        PostingRules.requireBalanced(entries);
        Integer existing = jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE posting_id = ?",
                Integer.class, postingId);
        if (existing != null && existing > 0) {
            return false;
        }
        List<Object[]> rows = new ArrayList<>();
        for (LedgerEntry e : entries) {
            rows.add(new Object[] {postingId, transactionId, type.name(), e.accountCode(), e.direction().name(),
                    e.amount().amount(), e.amount().currency(), Timestamp.from(now)});
        }
        try {
            jdbc.batchUpdate("""
                    INSERT INTO ledger_entries (posting_id, transaction_id, posting_type, account_code, direction,
                                                amount, currency, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", rows);
        } catch (DuplicateKeyException concurrent) {
            return false;
        }
        return true;
    }

    public boolean hasPosting(UUID transactionId, PostingRules.PostingType type) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE transaction_id = ? AND posting_type = ?",
                Integer.class, transactionId, type.name());
        return n != null && n > 0;
    }

    public List<StoredEntry> entriesOf(UUID transactionId) {
        return jdbc.query("""
                        SELECT id, posting_id, posting_type, account_code, direction, amount, currency, created_at
                        FROM ledger_entries WHERE transaction_id = ? ORDER BY id""",
                (rs, i) -> new StoredEntry(rs.getLong(1), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        rs.getString(5), new Money(rs.getBigDecimal(6), rs.getString(7)),
                        rs.getTimestamp(8).toInstant()),
                transactionId);
    }

    public List<AccountTotals> totals(String accountCode) {
        return jdbc.query("""
                        SELECT currency,
                               COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0),
                               COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0)
                        FROM ledger_entries WHERE account_code = ? GROUP BY currency ORDER BY currency""",
                (rs, i) -> new AccountTotals(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3)), accountCode);
    }

    public List<AccountTotals> grandTotals() {
        return jdbc.query("""
                        SELECT currency,
                               COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0),
                               COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0)
                        FROM ledger_entries GROUP BY currency ORDER BY currency""",
                (rs, i) -> new AccountTotals(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3)));
    }

    public long unbalancedPostings() {
        return count("""
                SELECT count(*) FROM (
                    SELECT posting_id FROM ledger_entries GROUP BY posting_id, currency
                    HAVING SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) <> 0) t""");
    }

    /** Captured payments / completed refunds that have no ledger posting (should be 0). */
    public long settledTransactionsWithoutPosting() {
        return count("""
                SELECT count(*) FROM transactions t
                WHERE ((t.type = 'PAYMENT' AND t.status = 'CAPTURED') OR (t.type = 'REFUND' AND t.status = 'COMPLETED'))
                  AND NOT EXISTS (SELECT 1 FROM ledger_entries e WHERE e.transaction_id = t.id)""");
    }

    /** Merchant payable accounts with a debit balance: a refund posted without a capture. */
    public long negativeMerchantPayables() {
        return count("""
                SELECT count(*) FROM (
                    SELECT account_code FROM ledger_entries WHERE account_code LIKE 'MERCHANT_PAYABLE:%'
                    GROUP BY account_code, currency
                    HAVING SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END) < 0) t""");
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }
}
