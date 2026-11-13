package com.hpg.reconciliation.infrastructure.persistence;

import com.hpg.reconciliation.matching.SettlementRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Provider rows as received, written in chunks while the file streams in. Append-only (DB trigger). */
@Component
public class SettlementRecordStore {

    private final JdbcTemplate jdbc;

    public SettlementRecordStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record StoredRow(long id, SettlementRow row) {
    }

    public void insert(UUID batchId, List<SettlementRow> rows) {
        List<Object[]> args = new ArrayList<>(rows.size());
        for (SettlementRow r : rows) {
            args.add(new Object[] {batchId, r.rowNumber(), r.entityId(), r.entityType(), r.orderId(), r.amount(), r.fee(),
                    r.tax(), r.currency(), r.settlementId(), ts(r.settledAt()), ts(r.createdAt()), r.raw(),
                    r.parseError() == null || r.parseError().length() <= 500 ? r.parseError() : r.parseError().substring(0, 500)});
        }
        jdbc.batchUpdate("""
                INSERT INTO settlement_records (batch_id, row_number, provider_entity_id, entity_type, provider_order_id,
                    amount, fee, tax, currency, settlement_id, settled_at, provider_created_at, raw_row, parse_error)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", args);
    }

    public List<StoredRow> load(UUID batchId) {
        return jdbc.query("""
                        SELECT id, row_number, provider_entity_id, entity_type, provider_order_id, amount, fee, tax,
                               currency, settlement_id, settled_at, provider_created_at, raw_row, parse_error
                        FROM settlement_records WHERE batch_id = ? ORDER BY row_number""",
                (rs, i) -> new StoredRow(rs.getLong(1), new SettlementRow(rs.getInt(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getString(9),
                        rs.getString(10), instant(rs.getTimestamp(11)), instant(rs.getTimestamp(12)), rs.getString(13),
                        rs.getString(14))),
                batchId);
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
