package com.hpg.payment.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * PostgreSQL transaction-scoped advisory locks.
 *
 * <p>Used to serialise payment creation per merchant: the daily-limit check ("sum so far + this amount
 * &lt;= limit") and the Idempotency-Key check are check-then-act operations that would otherwise race.
 * An advisory lock locks an arbitrary number (here: a hash of the merchant id) rather than a row, so it
 * works even though the rows being checked do not exist yet. It is released automatically at commit or
 * rollback. Trade-off: creations for one merchant run one at a time (each holds the lock for a few
 * milliseconds); different merchants never block each other.
 */
@Component
public class MerchantLocks {

    private final JdbcTemplate jdbc;

    public MerchantLocks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockPaymentCreation(UUID merchantId) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class,
                "payment-create:" + merchantId);
    }
}
