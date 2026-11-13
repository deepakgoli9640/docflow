package com.hpg.transaction.application;

import com.hpg.common.error.BusinessException;
import com.hpg.common.error.NotFoundException;
import com.hpg.common.money.Money;
import com.hpg.transaction.domain.Transaction;
import com.hpg.transaction.domain.TransactionErrorCode;
import com.hpg.transaction.domain.TransactionStatus;
import com.hpg.transaction.domain.TransactionType;
import com.hpg.transaction.infrastructure.persistence.LedgerStore;
import com.hpg.transaction.infrastructure.persistence.TransactionRepository;
import com.hpg.transaction.infrastructure.persistence.TransactionTimeline;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class TransactionQueryService {

    private static final Duration MAX_REPORT_PERIOD = Duration.ofDays(366);

    private final TransactionRepository transactions;
    private final TransactionTimeline timeline;
    private final LedgerStore ledger;
    private final JdbcTemplate jdbc;

    public TransactionQueryService(TransactionRepository transactions, TransactionTimeline timeline, LedgerStore ledger,
            JdbcTemplate jdbc) {
        this.transactions = transactions;
        this.timeline = timeline;
        this.ledger = ledger;
        this.jdbc = jdbc;
    }

    public record Filter(TransactionType type, TransactionStatus status, UUID paymentId, Instant from, Instant to) {
    }

    public record Details(Transaction transaction, List<TransactionTimeline.Entry> events,
            List<LedgerStore.StoredEntry> ledgerEntries) {
    }

    /** Totals per currency for a period, by completion time (captured / refund completed). */
    public record Summary(String currency, long capturedCount, Money capturedAmount, long refundedCount,
            Money refundedAmount, Money netAmount) {
    }

    public Page<Transaction> list(UUID merchantId, Filter f, Pageable pageable) {
        Specification<Transaction> spec = (root, q, cb) -> cb.equal(root.get("merchantId"), merchantId);
        if (f.type() != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("type"), f.type()));
        }
        if (f.status() != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), f.status()));
        }
        if (f.paymentId() != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("paymentId"), f.paymentId()));
        }
        if (f.from() != null) {
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("initiatedAt"), f.from()));
        }
        if (f.to() != null) {
            spec = spec.and((root, q, cb) -> cb.lessThan(root.get("initiatedAt"), f.to()));
        }
        return transactions.findAll(spec, pageable);
    }

    public Details get(UUID merchantId, String transactionRef) {
        Transaction t = transactions.findByTransactionRefAndMerchantId(transactionRef, merchantId)
                .orElseThrow(() -> new NotFoundException(TransactionErrorCode.TRANSACTION_NOT_FOUND,
                        "Transaction " + transactionRef + " not found"));
        return new Details(t, timeline.of(t.getId()), ledger.entriesOf(t.getId()));
    }

    public List<Summary> summary(UUID merchantId, Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to) || Duration.between(from, to).compareTo(MAX_REPORT_PERIOD) > 0) {
            throw new BusinessException(TransactionErrorCode.INVALID_PERIOD,
                    "Give from < to, at most 366 days apart (ISO-8601 instants).");
        }
        record Row(String type, String currency, long count, BigDecimal total) {
        }
        List<Row> rows = jdbc.query("""
                        SELECT type, currency, count(*), COALESCE(SUM(amount), 0) FROM transactions
                        WHERE merchant_id = ? AND completed_at >= ? AND completed_at < ?
                          AND ((type = 'PAYMENT' AND status = 'CAPTURED') OR (type = 'REFUND' AND status = 'COMPLETED'))
                        GROUP BY type, currency""",
                (rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getBigDecimal(4)),
                merchantId, Timestamp.from(from), Timestamp.from(to));
        Map<String, long[]> counts = new TreeMap<>();
        Map<String, BigDecimal[]> totals = new TreeMap<>();
        for (Row r : rows) {
            int idx = "PAYMENT".equals(r.type()) ? 0 : 1;
            counts.computeIfAbsent(r.currency(), c -> new long[2])[idx] = r.count();
            totals.computeIfAbsent(r.currency(), c -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO})[idx] = r.total();
        }
        return counts.keySet().stream().map(c -> {
            BigDecimal[] t = totals.get(c);
            return new Summary(c, counts.get(c)[0], new Money(t[0], c), counts.get(c)[1], new Money(t[1], c),
                    new Money(t[0].subtract(t[1]), c));
        }).toList();
    }
}
