package com.hpg.refund.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** refund-service's copy of the merchant facts it needs. Written only from merchant snapshots. */
@Component
public class MerchantReplicaStore {

    private final JdbcTemplate jdbc;

    public MerchantReplicaStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record MerchantReplica(UUID merchantId, String status, int refundWindowDays, long snapshotVersion) {

        public boolean isActive() {
            return "ACTIVE".equals(status);
        }
    }

    /** Applies a snapshot unless an equal or newer version is already stored. @return true if applied */
    public boolean upsert(UUID merchantId, String status, int refundWindowDays, long version, Instant now) {
        return jdbc.update("""
                        INSERT INTO merchant_replica (merchant_id, status, refund_window_days, snapshot_version, updated_at)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (merchant_id) DO UPDATE
                           SET status = EXCLUDED.status, refund_window_days = EXCLUDED.refund_window_days,
                               snapshot_version = EXCLUDED.snapshot_version, updated_at = EXCLUDED.updated_at
                         WHERE merchant_replica.snapshot_version < EXCLUDED.snapshot_version""",
                merchantId, status, refundWindowDays, version, Timestamp.from(now)) == 1;
    }

    public Optional<MerchantReplica> find(UUID merchantId) {
        return jdbc.query("SELECT merchant_id, status, refund_window_days, snapshot_version FROM merchant_replica WHERE merchant_id = ?",
                (rs, i) -> new MerchantReplica(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), rs.getLong(4)),
                merchantId).stream().findFirst();
    }
}
