package com.hpg.reconciliation.infrastructure.persistence;

import com.hpg.reconciliation.matching.InternalTransaction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Our side of reconciliation: ledger bookings replicated from ledger.events.v1. */
@Component
public class InternalTransactionStore {

    private static final int IN_CHUNK = 500;
    private static final RowMapper<InternalTransaction> MAPPER = (rs, i) -> new InternalTransaction(rs.getString(1),
            rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getBigDecimal(5), rs.getString(6),
            rs.getTimestamp(7).toInstant());
    private static final String COLUMNS =
            "transaction_ref, merchant_id, transaction_type, provider_reference, amount, currency, posted_at";

    private final JdbcTemplate jdbc;

    public InternalTransactionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if new (a redelivered event changes nothing) */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean insert(String transactionRef, UUID transactionId, UUID merchantId, String type, UUID paymentId,
            UUID refundId, String provider, String providerReference, java.math.BigDecimal amount, String currency,
            Instant postedAt, Instant now) {
        return jdbc.update("""
                        INSERT INTO internal_transactions (transaction_ref, transaction_id, merchant_id, transaction_type,
                            payment_id, refund_id, provider, provider_reference, amount, currency, posted_at, received_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (transaction_ref) DO NOTHING""",
                transactionRef, transactionId, merchantId, type, paymentId, refundId, provider, providerReference, amount,
                currency, Timestamp.from(postedAt), Timestamp.from(now)) == 1;
    }

    /** Internal transactions per provider reference, for the references in a report. */
    public Map<String, List<InternalTransaction>> byProviderReference(Collection<String> refs) {
        Map<String, List<InternalTransaction>> out = new HashMap<>();
        List<String> all = new ArrayList<>(new HashSet<>(refs));
        for (int i = 0; i < all.size(); i += IN_CHUNK) {
            List<String> chunk = all.subList(i, Math.min(all.size(), i + IN_CHUNK));
            String placeholders = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            jdbc.query("SELECT " + COLUMNS + " FROM internal_transactions WHERE provider_reference IN (" + placeholders + ")",
                    MAPPER, chunk.toArray()).forEach(t -> out.computeIfAbsent(t.providerReference(), k -> new ArrayList<>()).add(t));
        }
        return out;
    }

    /**
     * Transactions the provider should have settled by now: posted before {@code dueBefore}, after
     * {@code lookbackFrom}, and neither matched nor resolved in any completed batch.
     */
    public List<InternalTransaction> dueUnsettled(Instant dueBefore, Instant lookbackFrom) {
        return jdbc.query("SELECT " + COLUMNS + """
                         FROM internal_transactions t
                        WHERE t.posted_at < ? AND t.posted_at >= ?
                          AND NOT EXISTS (SELECT 1 FROM reconciliation_records r
                                            JOIN reconciliation_batches b ON b.id = r.batch_id AND b.status = 'COMPLETED'
                                           WHERE r.internal_transaction_ref = t.transaction_ref
                                             AND (r.status = 'MATCHED' OR r.resolution_status = 'RESOLVED'))
                        ORDER BY t.posted_at""",
                MAPPER, Timestamp.from(dueBefore), Timestamp.from(lookbackFrom));
    }

    /** References already matched in a completed batch of a different business date. */
    public Set<String> settledInOtherDays(Collection<String> refs, LocalDate businessDate) {
        Set<String> out = new HashSet<>();
        List<String> all = new ArrayList<>(new HashSet<>(refs));
        for (int i = 0; i < all.size(); i += IN_CHUNK) {
            List<String> chunk = all.subList(i, Math.min(all.size(), i + IN_CHUNK));
            String placeholders = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            List<Object> args = new ArrayList<>(chunk);
            args.add(java.sql.Date.valueOf(businessDate));
            out.addAll(jdbc.queryForList("""
                            SELECT DISTINCT r.provider_reference FROM reconciliation_records r
                              JOIN reconciliation_batches b ON b.id = r.batch_id AND b.status = 'COMPLETED'
                             WHERE r.status = 'MATCHED' AND r.provider_reference IN (""" + placeholders + ") AND b.business_date <> ?",
                    String.class, args.toArray()));
        }
        return out;
    }
}
