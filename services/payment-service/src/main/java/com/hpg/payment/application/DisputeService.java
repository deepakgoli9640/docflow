package com.hpg.payment.application;

import com.hpg.common.error.BusinessException;
import com.hpg.common.id.Ids;
import com.hpg.common.money.Money;
import com.hpg.events.Topics;
import com.hpg.events.dispute.DisputeEventPayload;
import com.hpg.events.dispute.DisputeEventType;
import com.hpg.messaging.outbox.OutboxWriter;
import com.hpg.payment.domain.PaymentErrorCode;
import com.hpg.payment.infrastructure.persistence.PaymentAttemptRepository;
import com.hpg.payment.provider.PaymentProvider;
import com.hpg.payment.provider.ProviderDispute;
import com.hpg.payment.provider.resilience.PaymentProviders;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Disputes (chargebacks) on captured payments.
 *
 * <ul>
 *   <li><b>Source of truth is the provider.</b> Webhooks (and the answers to accept / contest) carry its view;
 *       this service applies it forward-only: OPEN → UNDER_REVIEW → WON | LOST | CLOSED.</li>
 *   <li><b>Money follows what the provider actually did.</b> When the deducted amount grows, a
 *       {@code DisputeFundsDeducted} event books a chargeback in the ledger; when a won dispute gives it back,
 *       {@code DisputeFundsReinstated} reverses it. A LOST dispute counts as fully deducted, a WON one as
 *       fully returned, whatever the order the webhooks arrive in.</li>
 *   <li><b>The hospital decides</b> whether to accept or contest (with a summary of its evidence) before the
 *       provider's deadline. Nothing is accepted automatically.</li>
 * </ul>
 * Events go to {@code dispute.events.v1} through the outbox, keyed by payment.
 */
@Service
public class DisputeService {

    private static final Logger log = LoggerFactory.getLogger(DisputeService.class);
    private static final Map<String, Integer> RANK = Map.of("OPEN", 0, "UNDER_REVIEW", 1, "WON", 2, "LOST", 2, "CLOSED", 2);
    private static final Set<String> TERMINAL = Set.of("WON", "LOST", "CLOSED");

    public enum Outcome { APPLIED, NO_CHANGE, UNKNOWN_PAYMENT }

    /** A dispute as stored. */
    public record Dispute(UUID id, UUID paymentId, UUID merchantId, String provider, String providerDisputeId,
            String providerPaymentId, Money amount, Money amountDeducted, Money deductionAnnounced, String status,
            String reasonCode, String phase, Instant respondBy, String merchantResponse, String responseSummary,
            long version, Instant createdAt, Instant updatedAt, Instant resolvedAt) {

        public boolean isOpenForResponse() {
            return "OPEN".equals(status) && merchantResponse == null;
        }
    }

    private final JdbcTemplate jdbc;
    private final PaymentAttemptRepository attempts;
    private final PaymentProviders providers;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;
    private final Clock clock;

    public DisputeService(JdbcTemplate jdbc, PaymentAttemptRepository attempts, PaymentProviders providers,
            OutboxWriter outbox, TransactionTemplate tx, MeterRegistry meters, Clock clock) {
        this.jdbc = jdbc;
        this.attempts = attempts;
        this.providers = providers;
        this.outbox = outbox;
        this.tx = tx;
        this.meters = meters;
        this.clock = clock;
    }

    // --- provider side ------------------------------------------------------------------------------------

    /** Applies the provider's view of a dispute. Runs inside the webhook's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome onProviderDispute(String provider, ProviderDispute d, String causationId) {
        Optional<Dispute> existing = lock(provider, d.providerDisputeId());
        Instant now = clock.instant();
        Dispute current;
        if (existing.isPresent()) {
            current = existing.get();
        } else {
            var attempt = attempts.findByProviderPaymentId(d.providerPaymentId());
            if (attempt.isEmpty()) {
                log.warn("Dispute {} names unknown provider payment {}", d.providerDisputeId(), d.providerPaymentId());
                return Outcome.UNKNOWN_PAYMENT;
            }
            UUID paymentId = attempt.get().getPaymentId();
            UUID merchantId = jdbc.queryForObject("SELECT merchant_id FROM payments WHERE id = ?", UUID.class, paymentId);
            UUID id = Ids.newId();
            jdbc.update("""
                    INSERT INTO disputes (id, payment_id, merchant_id, provider, provider_dispute_id, provider_payment_id,
                                          amount, currency, status, reason_code, phase, respond_by, version, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, 0, ?, ?)""",
                    id, paymentId, merchantId, provider, d.providerDisputeId(), d.providerPaymentId(), d.amount().amount(),
                    d.amount().currency(), d.reasonCode(), d.phase(), ts(d.respondBy()), Timestamp.from(now), Timestamp.from(now));
            current = lock(provider, d.providerDisputeId()).orElseThrow();
            publish(current, DisputeEventType.DisputeOpened, null, causationId);
            meters.counter("disputes.opened", "provider", provider).increment();
            log.info("Dispute {} opened on payment {} for {} (reason {})", d.providerDisputeId(), paymentId, d.amount(),
                    d.reasonCode());
        }
        return apply(current, d, causationId, now) ? Outcome.APPLIED : (existing.isPresent() ? Outcome.NO_CHANGE : Outcome.APPLIED);
    }

    private boolean apply(Dispute current, ProviderDispute d, String causationId, Instant now) {
        String status = RANK.containsKey(d.status()) ? d.status() : current.status();
        if (TERMINAL.contains(current.status()) || RANK.get(status) < RANK.get(current.status())) {
            status = current.status();      // forward-only: a late "open" never reopens a decided dispute
        }
        // What the provider holds back after this report. LOST = everything, WON = nothing.
        BigDecimal deducted = switch (status) {
            case "LOST" -> d.amount().amount().max(d.amountDeducted().amount());
            case "WON" -> BigDecimal.ZERO;
            default -> d.amountDeducted().amount().max(current.amountDeducted().amount());
        };
        BigDecimal delta = deducted.subtract(current.deductionAnnounced().amount());
        boolean statusChanged = !status.equals(current.status());
        if (!statusChanged && delta.signum() == 0 && deducted.compareTo(current.amountDeducted().amount()) == 0) {
            return false;
        }
        boolean resolving = statusChanged && TERMINAL.contains(status);
        jdbc.update("""
                UPDATE disputes SET status = ?, amount_deducted = ?, deduction_announced = ?, respond_by = COALESCE(?, respond_by),
                                    phase = COALESCE(?, phase), version = version + 1, updated_at = ?,
                                    resolved_at = CASE WHEN ? THEN ? ELSE resolved_at END
                WHERE id = ?""",
                status, deducted, deducted, ts(d.respondBy()), d.phase(), Timestamp.from(now), resolving, Timestamp.from(now),
                current.id());
        Dispute updated = find(current.id()).orElseThrow();
        String currency = current.amount().currency();
        if (delta.signum() > 0) {
            publish(updated, DisputeEventType.DisputeFundsDeducted, new Money(delta, currency), causationId);
        } else if (delta.signum() < 0) {
            publish(updated, DisputeEventType.DisputeFundsReinstated, new Money(delta.negate(), currency), causationId);
        }
        if (resolving) {
            publish(updated, DisputeEventType.DisputeResolved, null, causationId);
            meters.counter("disputes.resolved", "status", status).increment();
        }
        log.info("Dispute {} {} -> {} (deducted {} {})", current.providerDisputeId(), current.status(), status, deducted, currency);
        return true;
    }

    // --- hospital side ------------------------------------------------------------------------------------

    public List<Dispute> list(UUID merchantId, String status, int limit) {
        return status == null
                ? jdbc.query("SELECT * FROM disputes WHERE merchant_id = ? ORDER BY created_at DESC LIMIT ?", MAPPER, merchantId, limit)
                : jdbc.query("SELECT * FROM disputes WHERE merchant_id = ? AND status = ? ORDER BY created_at DESC LIMIT ?",
                        MAPPER, merchantId, status, limit);
    }

    /** One dispute of this hospital; another hospital's dispute is "not found". */
    public Dispute get(UUID merchantId, UUID disputeId) {
        return find(disputeId).filter(x -> x.merchantId().equals(merchantId))
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.DISPUTE_NOT_FOUND, "No dispute " + disputeId));
    }

    /** The hospital accepts the dispute (the patient keeps the money). */
    public Dispute accept(UUID merchantId, UUID disputeId) {
        Dispute d = requireOpen(merchantId, disputeId);
        PaymentProvider provider = providers.live();
        ProviderDispute result = provider.acceptDispute(d.providerDisputeId());   // outside any DB transaction
        return record(d, result, "ACCEPTED", null);
    }

    /** The hospital contests the dispute with a summary of its evidence. */
    public Dispute contest(UUID merchantId, UUID disputeId, String summary) {
        Dispute d = requireOpen(merchantId, disputeId);
        PaymentProvider provider = providers.live();
        ProviderDispute result = provider.contestDispute(d.providerDisputeId(), d.amount(), summary);
        return record(d, result, "CONTESTED", summary);
    }

    private Dispute requireOpen(UUID merchantId, UUID disputeId) {
        Dispute d = get(merchantId, disputeId);
        if (!d.isOpenForResponse()) {
            throw new BusinessException(PaymentErrorCode.DISPUTE_NOT_OPEN,
                    "Dispute is " + d.status() + (d.merchantResponse() == null ? "" : " and was already " + d.merchantResponse()));
        }
        return d;
    }

    private Dispute record(Dispute d, ProviderDispute result, String response, String summary) {
        return tx.execute(s -> {
            Dispute locked = lock(d.provider(), d.providerDisputeId()).orElseThrow();
            jdbc.update("UPDATE disputes SET merchant_response = ?, response_summary = ? WHERE id = ?", response, summary, d.id());
            apply(locked, result, null, clock.instant());
            meters.counter("disputes.responses", "response", response).increment();
            return find(d.id()).orElseThrow();
        });
    }

    // --- persistence --------------------------------------------------------------------------------------

    private Optional<Dispute> lock(String provider, String providerDisputeId) {
        return jdbc.query("SELECT * FROM disputes WHERE provider = ? AND provider_dispute_id = ? FOR UPDATE", MAPPER,
                provider, providerDisputeId).stream().findFirst();
    }

    private Optional<Dispute> find(UUID id) {
        return jdbc.query("SELECT * FROM disputes WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    private void publish(Dispute d, DisputeEventType type, Money moved, String causationId) {
        DisputeEventPayload payload = new DisputeEventPayload(d.id(), d.paymentId(), d.merchantId(), d.provider(),
                d.providerDisputeId(), d.providerPaymentId(), d.amount(), moved, d.status(), d.reasonCode(), d.phase(),
                d.respondBy(), clock.instant());
        outbox.append(Topics.DISPUTE_EVENTS, d.paymentId().toString(), "Dispute", d.id().toString(), d.version(),
                d.merchantId(), type.name(), DisputeEventType.CURRENT_VERSION, causationId, payload);
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static final RowMapper<Dispute> MAPPER = (rs, i) -> {
        String currency = rs.getString("currency");
        return new Dispute(rs.getObject("id", UUID.class), rs.getObject("payment_id", UUID.class),
                rs.getObject("merchant_id", UUID.class), rs.getString("provider"), rs.getString("provider_dispute_id"),
                rs.getString("provider_payment_id"), new Money(rs.getBigDecimal("amount"), currency),
                new Money(rs.getBigDecimal("amount_deducted"), currency), new Money(rs.getBigDecimal("deduction_announced"), currency),
                rs.getString("status"), rs.getString("reason_code"), rs.getString("phase"), instant(rs, "respond_by"),
                rs.getString("merchant_response"), rs.getString("response_summary"), rs.getLong("version"),
                instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "resolved_at"));
    };
}
