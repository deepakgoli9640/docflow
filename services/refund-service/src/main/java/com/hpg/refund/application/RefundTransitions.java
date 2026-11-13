package com.hpg.refund.application;

import com.hpg.events.Topics;
import com.hpg.events.refund.RefundEventPayload;
import com.hpg.events.refund.RefundEventType;
import com.hpg.messaging.outbox.OutboxWriter;
import com.hpg.persistence.Actors;
import com.hpg.refund.domain.Refund;
import com.hpg.refund.domain.RefundStatus;
import com.hpg.refund.domain.RefundStatusHistory;
import com.hpg.refund.domain.RefundablePayment;
import com.hpg.refund.infrastructure.persistence.RefundStatusHistoryRepository;
import com.hpg.refund.infrastructure.persistence.RefundablePaymentStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * The single place where a refund changes state. In the caller's transaction it applies the transition,
 * moves money between "reserved" and "refunded" (or releases it), writes the history row and appends
 * the outbox event — so state, amounts, audit trail and event can never disagree.
 */
@Component
public class RefundTransitions {

    private static final Logger log = LoggerFactory.getLogger(RefundTransitions.class);

    private final RefundStatusHistoryRepository history;
    private final RefundablePaymentStore refundables;
    private final OutboxWriter outbox;
    private final MeterRegistry meters;
    private final Clock clock;

    public RefundTransitions(RefundStatusHistoryRepository history, RefundablePaymentStore refundables,
            OutboxWriter outbox, MeterRegistry meters, Clock clock) {
        this.history = history;
        this.refundables = refundables;
        this.outbox = outbox;
        this.meters = meters;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void created(Refund refund, RefundablePayment payment, String causationId) {
        history.save(new RefundStatusHistory(refund.getId(), null, RefundStatus.CREATED, refund.getReason(),
                Actors.current(), clock.instant()));
        publish(refund, payment, RefundEventType.RefundInitiated, causationId);
        meters.counter("refunds.created").increment();
    }

    /** @return true if the refund changed state */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean apply(Refund refund, RefundStatus target, String reason, String causationId) {
        RefundStatus from = refund.getStatus();
        Instant now = clock.instant();
        if (!refund.transition(target, now)) {
            if (from != target) {
                log.info("Ignoring refund transition refundId={} {} -> {} (not allowed)", refund.getId(), from, target);
            }
            return false;
        }
        history.save(new RefundStatusHistory(refund.getId(), from, target, reason, Actors.current(), now));
        if (target == RefundStatus.COMPLETED) {
            refundables.completeReservation(refund.getPaymentId(), refund.amount(), now);
            publish(refund, refundables.find(refund.getPaymentId()).orElseThrow(), RefundEventType.RefundCompleted,
                    causationId);
        } else if (target == RefundStatus.FAILED) {
            refundables.releaseReservation(refund.getPaymentId(), refund.amount(), now);
            publish(refund, refundables.find(refund.getPaymentId()).orElseThrow(), RefundEventType.RefundFailed,
                    causationId);
        }
        meters.counter("refunds.transitions", "from", from.name(), "to", target.name()).increment();
        log.info("Refund transition refundId={} paymentId={} {} -> {}", refund.getId(), refund.getPaymentId(), from,
                target);
        return true;
    }

    private void publish(Refund r, RefundablePayment p, RefundEventType type, String causationId) {
        RefundEventPayload payload = new RefundEventPayload(r.getId(), r.getPaymentId(), r.getMerchantId(),
                p.merchantReference(), r.amount(), p.refunded(), r.getStatus().name(), r.getReason(),
                r.getProviderRefundId(), r.getFailureCode(), r.getFailureReason(), clock.instant());
        long version = r.getVersion() == null ? 0 : r.getVersion() + 1;
        // Keyed by paymentId: all refunds of one payment are ordered relative to each other.
        outbox.append(Topics.REFUND_EVENTS, r.getPaymentId().toString(), "Refund", r.getId().toString(), version,
                r.getMerchantId(), type.name(), RefundEventType.CURRENT_VERSION, causationId, payload);
    }
}
