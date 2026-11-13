package com.hpg.payment.application;

import com.hpg.events.Topics;
import com.hpg.events.payment.PaymentEventPayload;
import com.hpg.events.payment.PaymentEventType;
import com.hpg.messaging.outbox.OutboxWriter;
import com.hpg.payment.domain.Payment;
import com.hpg.payment.domain.PaymentStatus;
import com.hpg.payment.domain.PaymentStatusHistory;
import com.hpg.payment.domain.TransitionDecision;
import com.hpg.payment.domain.TransitionSource;
import com.hpg.payment.infrastructure.persistence.PaymentOrderRepository;
import com.hpg.payment.infrastructure.persistence.PaymentStatusHistoryRepository;
import com.hpg.persistence.Actors;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.EnumSet;
import java.util.Set;

/**
 * The single place where a payment changes state. In the caller's transaction it:
 * applies the state machine, writes the status history row, appends the outbox event, and records
 * metrics. Because all of that happens in one transaction, the state, its audit trail and its event can
 * never disagree.
 */
@Component
public class PaymentTransitions {

    private static final Logger log = LoggerFactory.getLogger(PaymentTransitions.class);
    /** Events that notification-service needs patient contact details for. */
    private static final Set<PaymentStatus> TERMINAL = EnumSet.of(PaymentStatus.CAPTURED, PaymentStatus.FAILED,
            PaymentStatus.CANCELLED, PaymentStatus.EXPIRED);
    private static final Set<PaymentStatus> CONTACT_EVENTS = EnumSet.of(PaymentStatus.CAPTURED, PaymentStatus.FAILED,
            PaymentStatus.EXPIRED);

    private final PaymentStatusHistoryRepository history;
    private final PaymentOrderRepository orders;
    private final OutboxWriter outbox;
    private final MeterRegistry meters;
    private final Clock clock;

    public PaymentTransitions(PaymentStatusHistoryRepository history, PaymentOrderRepository orders,
            OutboxWriter outbox, MeterRegistry meters, Clock clock) {
        this.history = history;
        this.orders = orders;
        this.outbox = outbox;
        this.meters = meters;
        this.clock = clock;
    }

    /** Records the creation of a payment (history + PaymentInitiated event). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void created(Payment payment, String causationId) {
        history.save(new PaymentStatusHistory(payment.getId(), null, PaymentStatus.CREATED,
                TransitionSource.MERCHANT_API, null, causationId, Actors.current(), clock.instant()));
        publish(payment, PaymentEventType.PaymentInitiated, null, null, causationId);
        meters.counter("payments.initiated", "capture_mode", payment.getCaptureMode().name()).increment();
    }

    /**
     * @param providerPaymentId provider attempt that caused this change, if any (included in the event)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public TransitionDecision apply(Payment payment, PaymentStatus target, TransitionSource source, String reason,
            String causationId, String providerPaymentId) {
        PaymentStatus from = payment.getStatus();
        TransitionDecision decision = payment.transition(target, source, clock.instant());
        if (decision == TransitionDecision.IGNORE_STALE) {
            log.info("Ignoring stale provider evidence paymentId={} current={} evidence={} source={}",
                    payment.getId(), from, target, source);
            meters.counter("payments.stale_evidence", "source", source.name()).increment();
            return decision;
        }
        if (decision != TransitionDecision.APPLY) {
            return decision;
        }
        history.save(new PaymentStatusHistory(payment.getId(), from, target, source, reason, causationId,
                Actors.current(), clock.instant()));
        PaymentEventType eventType = eventTypeFor(target);
        if (eventType != null) {
            publish(payment, eventType, from, providerPaymentId, causationId);
        }
        meters.counter("payments.transitions", "from", from.name(), "to", target.name(), "source", source.name())
                .increment();
        if (TERMINAL.contains(target)) {
            meters.counter("payments.terminal", "status", target.name()).increment();
        }
        if (target == PaymentStatus.CAPTURED) {
            meters.summary("payment.amount.captured", "currency", payment.amount().currency())
                    .record(payment.amount().amount().doubleValue());
        }
        if (payment.isLateCapture() && target == PaymentStatus.CAPTURED) {
            // Money arrived after we had given up on the payment. Not an error, but someone must look.
            log.warn("LATE CAPTURE paymentId={} merchantId={} previousStatus={}", payment.getId(),
                    payment.getMerchantId(), from);
            meters.counter("payments.late_capture").increment();
        }
        log.info("Payment transition paymentId={} {} -> {} source={}", payment.getId(), from, target, source);
        return decision;
    }

    private static PaymentEventType eventTypeFor(PaymentStatus status) {
        return switch (status) {
            case AUTHORIZED -> PaymentEventType.PaymentAuthorized;
            case CAPTURED -> PaymentEventType.PaymentCaptured;
            case FAILED -> PaymentEventType.PaymentFailed;
            case CANCELLED -> PaymentEventType.PaymentCancelled;
            case EXPIRED -> PaymentEventType.PaymentExpired;
            // CREATED/PENDING/PROCESSING/UNKNOWN are internal steps: no other service acts on them.
            default -> null;
        };
    }

    private void publish(Payment p, PaymentEventType type, PaymentStatus previous, String providerPaymentId,
            String causationId) {
        var order = orders.findFirstByPaymentIdOrderByCreatedAtDesc(p.getId()).orElse(null);
        Payment.Customer c = CONTACT_EVENTS.contains(p.getStatus()) ? p.customer() : null;
        PaymentEventPayload payload = new PaymentEventPayload(p.getId(), p.getMerchantId(), p.getMerchantReference(),
                p.amount(), p.getStatus().name(), previous == null ? null : previous.name(), p.getCaptureMode().name(),
                order == null ? null : order.getProvider(), order == null ? null : order.getProviderOrderId(),
                providerPaymentId, p.isLateCapture(), p.getFailureCode(), p.getFailureReason(), clock.instant(),
                c == null ? null : new PaymentEventPayload.Customer(c.name(), c.email(), c.phone()));
        long version = p.getVersion() == null ? 0 : p.getVersion() + 1;
        outbox.append(Topics.PAYMENT_EVENTS, "Payment", p.getId().toString(), version, p.getMerchantId(),
                type.name(), PaymentEventType.CURRENT_VERSION, causationId, payload);
    }
}
