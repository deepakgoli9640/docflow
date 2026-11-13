package com.hpg.refund.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Idempotency-Key records (same design as payment-service, docs/02-payment-lifecycle.md §6.1). */
@Component
public class IdempotencyStore {

    private final JdbcTemplate jdbc;

    public IdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record StoredKey(String requestHash, UUID resourceId, int responseStatus) {
    }

    public Optional<StoredKey> find(UUID merchantId, String operation, String key) {
        return jdbc.query("""
                        SELECT request_hash, resource_id, response_status FROM idempotency_keys
                        WHERE merchant_id = ? AND operation = ? AND idempotency_key = ?""",
                (rs, i) -> new StoredKey(rs.getString(1), rs.getObject(2, UUID.class), rs.getInt(3)),
                merchantId, operation, key).stream().findFirst();
    }

    /** @return true if inserted, false if the key already existed */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean insert(UUID merchantId, String operation, String key, String requestHash, UUID resourceId,
            int responseStatus, Instant now, Instant expiresAt) {
        return jdbc.update("""
                        INSERT INTO idempotency_keys (merchant_id, operation, idempotency_key, request_hash,
                                                      resource_id, response_status, created_at, expires_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (merchant_id, operation, idempotency_key) DO NOTHING""",
                merchantId, operation, key, requestHash, resourceId, responseStatus,
                Timestamp.from(now), Timestamp.from(expiresAt)) == 1;
    }

    public int deleteExpired(Instant now) {
        return jdbc.update("DELETE FROM idempotency_keys WHERE expires_at < ?", Timestamp.from(now));
    }
}
