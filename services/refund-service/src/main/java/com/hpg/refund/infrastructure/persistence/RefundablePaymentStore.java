package com.hpg.refund.infrastructure.persistence;

import com.hpg.common.money.Money;
import com.hpg.refund.domain.RefundablePayment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code refundable_payments} through plain SQL: the reservation must be ONE atomic statement that both
 * checks and reserves (docs/03-refund-lifecycle.md §3), which JPA's read-modify-write cannot express.
 */
@Component
public class RefundablePaymentStore {

    private static final RowMapper<RefundablePayment> MAPPER = (rs, i) -> new RefundablePayment(
            rs.getObject("payment_id", UUID.class), rs.getObject("merchant_id", UUID.class),
            rs.getString("merchant_reference"), rs.getString("provider"), rs.getString("provider_payment_id"),
            rs.getString("currency"), rs.getBigDecimal("captured_amount"), rs.getBigDecimal("reserved_amount"),
            rs.getBigDecimal("refunded_amount"), rs.getTimestamp("captured_at").toInstant());

    private final JdbcTemplate jdbc;

    public RefundablePaymentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if inserted; false if the payment was already known (duplicate capture event) */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean insertCaptured(UUID paymentId, UUID merchantId, String merchantReference, String provider,
            String providerPaymentId, Money captured, Instant capturedAt, UUID sourceEventId, Instant now) {
        return jdbc.update("""
                        INSERT INTO refundable_payments (payment_id, merchant_id, merchant_reference, provider,
                            provider_payment_id, currency, captured_amount, captured_at, source_event_id, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (payment_id) DO NOTHING""",
                paymentId, merchantId, merchantReference, provider, providerPaymentId, captured.currency(),
                captured.amount(), Timestamp.from(capturedAt), sourceEventId, Timestamp.from(now)) == 1;
    }

    public Optional<RefundablePayment> find(UUID paymentId, UUID merchantId) {
        return jdbc.query("SELECT * FROM refundable_payments WHERE payment_id = ? AND merchant_id = ?", MAPPER,
                paymentId, merchantId).stream().findFirst();
    }

    public Optional<RefundablePayment> find(UUID paymentId) {
        return jdbc.query("SELECT * FROM refundable_payments WHERE payment_id = ?", MAPPER, paymentId)
                .stream().findFirst();
    }

    /**
     * Check-and-reserve in one statement. Postgres re-evaluates the WHERE clause after acquiring the row lock,
     * so two concurrent refunds can never both pass.
     *
     * @return true if reserved; false if it would exceed the refundable amount
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean reserve(UUID paymentId, UUID merchantId, Money amount, Instant now) {
        return jdbc.update("""
                        UPDATE refundable_payments
                           SET reserved_amount = reserved_amount + ?, version = version + 1, updated_at = ?
                         WHERE payment_id = ? AND merchant_id = ? AND currency = ?
                           AND captured_amount - refunded_amount - reserved_amount >= ?""",
                amount.amount(), Timestamp.from(now), paymentId, merchantId, amount.currency(), amount.amount()) == 1;
    }

    /** Refund completed: the reserved amount becomes refunded. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void completeReservation(UUID paymentId, Money amount, Instant now) {
        requireOne(jdbc.update("""
                        UPDATE refundable_payments
                           SET reserved_amount = reserved_amount - ?, refunded_amount = refunded_amount + ?,
                               version = version + 1, updated_at = ?
                         WHERE payment_id = ?""",
                amount.amount(), amount.amount(), Timestamp.from(now), paymentId), paymentId);
    }

    /** Refund failed: the reservation is released. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseReservation(UUID paymentId, Money amount, Instant now) {
        requireOne(jdbc.update("""
                        UPDATE refundable_payments
                           SET reserved_amount = reserved_amount - ?, version = version + 1, updated_at = ?
                         WHERE payment_id = ?""",
                amount.amount(), Timestamp.from(now), paymentId), paymentId);
    }

    private static void requireOne(int updated, UUID paymentId) {
        if (updated != 1) {
            throw new IllegalStateException("refundable_payments row missing for " + paymentId);
        }
    }
}
