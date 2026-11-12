package com.hpg.merchant.infrastructure;

import com.hpg.merchant.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Database-level guarantees, tested with SQL that bypasses the application. These constraints are the
 * last line of defence if a bug ever slips past the Java validation.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class MerchantSchemaIT {

    @Autowired
    JdbcTemplate jdbc;

    private UUID insertMerchant(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO merchants (id, legal_name, display_name, contact_email, status, version,
                                       created_at, updated_at, created_by, updated_by)
                VALUES (?, 'L', 'D', ?, 'ACTIVE', 0, now(), now(), 'test', 'test')""", id, email);
        return id;
    }

    @Test
    void contactEmailIsUnique() {
        insertMerchant("unique@schema.example");
        assertThatThrownBy(() -> insertMerchant("unique@schema.example"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unknownStatusIsRejected() {
        UUID id = insertMerchant("status@schema.example");
        assertThatThrownBy(() -> jdbc.update("UPDATE merchants SET status = 'HACKED' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void inconsistentLimitsAreRejected() {
        UUID id = insertMerchant("limits@schema.example");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO merchant_configurations (merchant_id, enabled_methods, capture_mode, currency,
                    min_amount, max_amount_per_txn, daily_limit, payment_ttl_minutes, max_attempts,
                    refund_window_days, notify_patient_email, notify_patient_sms, version,
                    created_at, updated_at, created_by, updated_by)
                VALUES (?, '["UPI"]', 'AUTO', 'INR', 100.00, 50.00, 1000.00, 60, 3, 30, true, false, 0,
                        now(), now(), 't', 't')""", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void auditLogIsAppendOnly() {
        UUID id = insertMerchant("audit@schema.example");
        jdbc.update("INSERT INTO merchant_audit_log (merchant_id, action, actor, created_at) VALUES (?, 'X', 't', now())", id);
        assertThatThrownBy(() -> jdbc.update("UPDATE merchant_audit_log SET actor = 'someone-else' WHERE merchant_id = ?", id))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM merchant_audit_log WHERE merchant_id = ?", id))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_audit_log WHERE merchant_id = ?", Long.class, id))
                .isEqualTo(1);
    }

    @Test
    void revokedCredentialMustHaveRevocationTime() {
        UUID id = insertMerchant("cred@schema.example");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO merchant_credentials (id, merchant_id, key_id, secret_hash, status, version,
                    created_at, updated_at, created_by, updated_by)
                VALUES (?, ?, 'hpgk_x', 'h', 'REVOKED', 0, now(), now(), 't', 't')""", UUID.randomUUID(), id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
