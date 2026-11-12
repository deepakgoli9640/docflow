package com.hpg.payment.application;

import com.hpg.payment.domain.AttemptStatus;
import com.hpg.payment.domain.Payment;
import com.hpg.payment.domain.PaymentAttempt;
import com.hpg.payment.domain.PaymentOrder;
import com.hpg.payment.domain.PaymentStatus;
import com.hpg.payment.domain.TransitionDecision;
import com.hpg.payment.domain.TransitionSource;
import com.hpg.payment.infrastructure.persistence.PaymentAttemptRepository;
import com.hpg.payment.infrastructure.persistence.PaymentOrderRepository;
import com.hpg.payment.provider.ProviderPayment;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Turns what the provider says about a payment attempt into state changes. Every channel — checkout
 * callback, webhook, status poll, capture response — ends up here, so they all apply identical rules
 * and converge on the same result whatever order they arrive in.
 */
@Service
public class PaymentEvidenceService {

    private static final Logger log = LoggerFactory.getLogger(PaymentEvidenceService.class);

    private final PaymentOrderRepository orders;
    private final PaymentAttemptRepository attempts;
    private final PaymentTransitions transitions;
    private final MeterRegistry meters;

    public PaymentEvidenceService(PaymentOrderRepository orders, PaymentAttemptRepository attempts,
            PaymentTransitions transitions, MeterRegistry meters) {
        this.orders = orders;
        this.attempts = attempts;
        this.transitions = transitions;
        this.meters = meters;
    }

    /** Finds the order a provider payment belongs to; empty if it is not one of ours. */
    public Optional<PaymentOrder> orderFor(String provider, ProviderPayment evidence) {
        return evidence.providerOrderId() == null ? Optional.empty()
                : orders.findByProviderAndProviderOrderId(provider, evidence.providerOrderId());
    }

    /**
     * Applies everything the provider reports for an order. Successful attempts are applied first: if
     * failures were applied first they could exhaust the attempt budget and mark the payment FAILED just
     * before the capture arrives, which would then be misreported as a "late" capture.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyAll(Payment payment, PaymentOrder order, java.util.List<ProviderPayment> evidence,
            TransitionSource source, String causationId) {
        evidence.stream()
                .sorted(java.util.Comparator.comparingInt(PaymentEvidenceService::successRank))
                .forEach(pp -> apply(payment, order, pp, source, causationId));
    }

    private static int successRank(ProviderPayment p) {
        return switch (p.status()) {
            case CAPTURED, REFUNDED -> 0;
            case AUTHORIZED -> 1;
            case CREATED -> 2;
            case FAILED -> 3;
        };
    }

    /** Applies one provider payment to its payment, in the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public EvidenceOutcome apply(Payment payment, PaymentOrder order, ProviderPayment evidence, TransitionSource source,
            String causationId) {
        Optional<PaymentAttempt> known = attempts.findByProviderPaymentId(evidence.providerPaymentId());
        if (known.isPresent() && !known.get().getPaymentId().equals(payment.getId())) {
            // Provider ids are globally unique, so this means inconsistent data. Never let one payment's
            // evidence change another payment: refuse loudly and leave it to investigation.
            log.error("Provider payment {} belongs to paymentId={} but arrived for paymentId={}; ignored",
                    evidence.providerPaymentId(), known.get().getPaymentId(), payment.getId());
            meters.counter("payments.evidence_conflicts").increment();
            return EvidenceOutcome.NO_CHANGE;
        }
        PaymentAttempt attempt = known.orElseGet(() -> attempts.save(new PaymentAttempt(payment.getId(), order.getId(),
                        evidence.providerPaymentId(), evidence.method(), evidence.amount())));
        AttemptStatus attemptStatus = switch (evidence.status()) {
            case AUTHORIZED -> AttemptStatus.AUTHORIZED;
            case CAPTURED, REFUNDED -> AttemptStatus.CAPTURED;
            case FAILED -> AttemptStatus.FAILED;
            case CREATED -> AttemptStatus.CREATED;
        };
        boolean attemptChanged = attempt.advanceTo(attemptStatus, evidence.method(), evidence.errorCode(),
                evidence.errorDescription());

        TransitionDecision decision = switch (evidence.status()) {
            case CAPTURED, REFUNDED -> {
                detectDuplicateCapture(payment, attempt);
                yield transitions.apply(payment, PaymentStatus.CAPTURED, source, "provider reports captured",
                        causationId, evidence.providerPaymentId());
            }
            case AUTHORIZED -> transitions.apply(payment, PaymentStatus.AUTHORIZED, source,
                    "provider reports authorized", causationId, evidence.providerPaymentId());
            case FAILED -> applyFailure(payment, evidence, source, causationId);
            case CREATED -> TransitionDecision.NO_OP;
        };
        return switch (decision) {
            case APPLY -> EvidenceOutcome.APPLIED;
            case IGNORE_STALE -> EvidenceOutcome.STALE_IGNORED;
            default -> attemptChanged ? EvidenceOutcome.APPLIED : EvidenceOutcome.NO_CHANGE;
        };
    }

    /**
     * A failed attempt does not fail the payment — the patient may retry — unless the attempt budget is
     * exhausted, or the payment was in PROCESSING (the patient's only attempt in flight just failed).
     */
    private TransitionDecision applyFailure(Payment payment, ProviderPayment evidence, TransitionSource source,
            String causationId) {
        PaymentStatus status = payment.getStatus();
        if (status != PaymentStatus.PENDING && status != PaymentStatus.PROCESSING && status != PaymentStatus.UNKNOWN) {
            return TransitionDecision.NO_OP;
        }
        long failed = attempts.countByPaymentIdAndStatus(payment.getId(), AttemptStatus.FAILED);
        if (status == PaymentStatus.PENDING && failed < payment.getMaxAttempts()) {
            log.info("Attempt {} failed for paymentId={} ({}/{} attempts used)", evidence.providerPaymentId(),
                    payment.getId(), failed, payment.getMaxAttempts());
            return TransitionDecision.NO_OP;
        }
        payment.recordFailure(evidence.errorCode(), evidence.errorDescription());
        return transitions.apply(payment, PaymentStatus.FAILED, source,
                status == PaymentStatus.PENDING ? "maximum payment attempts reached" : "provider reports failed",
                causationId, evidence.providerPaymentId());
    }

    private void detectDuplicateCapture(Payment payment, PaymentAttempt attempt) {
        if (payment.getStatus() != PaymentStatus.CAPTURED) {
            return;
        }
        boolean otherCaptured = attempts.findByPaymentIdOrderByCreatedAtAsc(payment.getId()).stream()
                .anyMatch(a -> !a.getId().equals(attempt.getId()) && a.getStatus() == AttemptStatus.CAPTURED);
        if (otherCaptured && !attempt.isDuplicateCapture()) {
            attempt.flagDuplicateCapture();
            log.warn("DUPLICATE CAPTURE paymentId={} providerPaymentId={}: the patient was charged twice; "
                    + "reconciliation will flag it and a refund decision is needed", payment.getId(),
                    attempt.getProviderPaymentId());
            meters.counter("payments.duplicate_capture").increment();
        }
    }
}
