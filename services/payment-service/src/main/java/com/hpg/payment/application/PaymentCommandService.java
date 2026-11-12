package com.hpg.payment.application;

import com.hpg.common.error.BusinessException;
import com.hpg.payment.domain.AttemptStatus;
import com.hpg.payment.domain.Payment;
import com.hpg.payment.domain.PaymentAttempt;
import com.hpg.payment.domain.PaymentErrorCode;
import com.hpg.payment.domain.PaymentOrder;
import com.hpg.payment.domain.PaymentStatus;
import com.hpg.payment.domain.TransitionSource;
import com.hpg.payment.infrastructure.persistence.PaymentAttemptRepository;
import com.hpg.payment.infrastructure.persistence.PaymentOrderRepository;
import com.hpg.payment.provider.PaymentProvider;
import com.hpg.payment.provider.ProviderAuthenticationException;
import com.hpg.payment.provider.ProviderPayment;
import com.hpg.payment.provider.ProviderRejectedException;
import com.hpg.payment.provider.ProviderTimeoutException;
import com.hpg.payment.provider.ProviderUnavailableException;
import com.hpg.payment.provider.resilience.PaymentProviders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Merchant-initiated changes: capture and cancel. */
@Service
public class PaymentCommandService {

    static final String CAPTURE = "CAPTURE_PAYMENT";
    static final String CANCEL = "CANCEL_PAYMENT";
    private static final Logger log = LoggerFactory.getLogger(PaymentCommandService.class);

    private final PaymentLookup lookup;
    private final PaymentAttemptRepository attempts;
    private final PaymentOrderRepository orders;
    private final PaymentEvidenceService evidence;
    private final PaymentTransitions transitions;
    private final IdempotencyService idempotency;
    private final PaymentProviders providers;
    private final TransactionTemplate tx;

    public PaymentCommandService(PaymentLookup lookup, PaymentAttemptRepository attempts, PaymentOrderRepository orders,
            PaymentEvidenceService evidence, PaymentTransitions transitions, IdempotencyService idempotency,
            PaymentProviders providers, TransactionTemplate tx) {
        this.lookup = lookup;
        this.attempts = attempts;
        this.orders = orders;
        this.evidence = evidence;
        this.transitions = transitions;
        this.idempotency = idempotency;
        this.providers = providers;
        this.tx = tx;
    }

    /**
     * Captures an AUTHORIZED payment (full amount). Outcomes:
     * success → 200 CAPTURED; provider timeout → 202 UNKNOWN (verified by the recovery job, never blindly
     * retried); rejection → 422 (payment unchanged); provider unavailable → 503 (payment unchanged).
     */
    @PreAuthorize("hasRole('MERCHANT')")
    public PaymentResult capture(UUID merchantId, UUID paymentId, String key) {
        idempotency.requireValidKey(key);
        String hash = idempotency.hash(Map.of("operation", CAPTURE, "paymentId", paymentId));
        Optional<IdempotencyService.Replay> replay = idempotency.findReplay(merchantId, CAPTURE, key, hash);
        if (replay.isPresent()) {
            return new PaymentResult(lookup.requireOwned(merchantId, paymentId), replay.get().responseStatus(), true);
        }
        Payment payment = lookup.requireOwned(merchantId, paymentId);
        if (payment.getStatus() == PaymentStatus.CAPTURED) {
            return recordOnly(merchantId, paymentId, key, hash, CAPTURE, 200); // naturally idempotent
        }
        if (payment.getStatus() != PaymentStatus.AUTHORIZED) {
            throw new BusinessException(PaymentErrorCode.INVALID_STATE_TRANSITION,
                    "Only AUTHORIZED payments can be captured; this one is " + payment.getStatus() + ".",
                    Map.of("currentStatus", payment.getStatus().name()));
        }
        PaymentAttempt authorized = authorizedAttempt(paymentId);
        PaymentProvider provider = providers.live();
        try {
            ProviderPayment captured = provider.capture(authorized.getProviderPaymentId(), payment.amount());
            return OptimisticRetry.run("capture", () -> tx.execute(status -> {
                Payment p = lookup.require(paymentId);
                PaymentOrder order = orders.findById(authorized.getPaymentOrderId()).orElseThrow();
                evidence.apply(p, order, captured, TransitionSource.PROVIDER_RESPONSE, key);
                idempotency.record(merchantId, CAPTURE, key, hash, paymentId, 200);
                return new PaymentResult(p, 200, false);
            }));
        } catch (ProviderTimeoutException e) {
            log.warn("Capture outcome unknown for paymentId={}: {}", paymentId, e.getMessage());
            return OptimisticRetry.run("capture-unknown", () -> tx.execute(status -> {
                Payment p = lookup.require(paymentId);
                transitions.apply(p, PaymentStatus.UNKNOWN, TransitionSource.SYSTEM,
                        "capture timed out; outcome unknown until verified", key, authorized.getProviderPaymentId());
                idempotency.record(merchantId, CAPTURE, key, hash, paymentId, 202);
                return new PaymentResult(p, 202, false);
            }));
        } catch (ProviderRejectedException e) {
            throw new BusinessException(PaymentErrorCode.PROVIDER_REJECTED, "The provider refused the capture.",
                    Map.of("providerErrorCode", e.providerErrorCode()));
        } catch (ProviderUnavailableException e) {
            throw new BusinessException(PaymentErrorCode.PROVIDER_UNAVAILABLE,
                    "The provider is unavailable; the payment is still AUTHORIZED. Retry with the same Idempotency-Key.");
        } catch (ProviderAuthenticationException e) {
            throw new BusinessException(PaymentErrorCode.PROVIDER_CONFIGURATION_ERROR,
                    "Payment provider configuration error. Operators have been alerted.");
        }
    }

    /**
     * Cancels a payment that has not been captured. For a PENDING payment the provider is asked first: if
     * the patient has just paid, the capture is applied and the cancel is refused instead of hiding the money.
     */
    @PreAuthorize("hasRole('MERCHANT')")
    public PaymentResult cancel(UUID merchantId, UUID paymentId, String key, String reason) {
        idempotency.requireValidKey(key);
        String hash = idempotency.hash(Map.of("operation", CANCEL, "paymentId", paymentId,
                "reason", reason == null ? "" : reason));
        Optional<IdempotencyService.Replay> replay = idempotency.findReplay(merchantId, CANCEL, key, hash);
        if (replay.isPresent()) {
            return new PaymentResult(lookup.requireOwned(merchantId, paymentId), replay.get().responseStatus(), true);
        }
        Payment payment = lookup.requireOwned(merchantId, paymentId);
        if (payment.getStatus() == PaymentStatus.PENDING) {
            refreshFromProvider(paymentId);
        }
        return OptimisticRetry.run("cancel", () -> tx.execute(status -> {
            Payment p = lookup.require(paymentId);
            if (p.getStatus() == PaymentStatus.CAPTURED) {
                throw new BusinessException(PaymentErrorCode.PAYMENT_ALREADY_CAPTURED,
                        "The patient has already paid; use a refund instead of cancelling.");
            }
            transitions.apply(p, PaymentStatus.CANCELLED, TransitionSource.MERCHANT_API, reason, key, null);
            idempotency.record(merchantId, CANCEL, key, hash, paymentId, 200);
            return new PaymentResult(p, 200, false);
        }));
    }

    /** Best effort: if the provider is unreachable the cancel proceeds (a late capture is still caught later). */
    private void refreshFromProvider(UUID paymentId) {
        Optional<PaymentOrder> order = orders.findFirstByPaymentIdOrderByCreatedAtDesc(paymentId);
        if (order.isEmpty()) {
            return;
        }
        try {
            List<ProviderPayment> found = providers.live().fetchOrderPayments(order.get().getProviderOrderId());
            OptimisticRetry.run("cancel-refresh", () -> tx.execute(status -> {
                Payment p = lookup.require(paymentId);
                evidence.applyAll(p, order.get(), found, TransitionSource.PROVIDER_POLL, null);
                return null;
            }));
        } catch (ProviderUnavailableException | ProviderTimeoutException e) {
            log.warn("Could not check provider before cancelling paymentId={}: {}", paymentId, e.getMessage());
        }
    }

    private PaymentResult recordOnly(UUID merchantId, UUID paymentId, String key, String hash, String op, int status) {
        return tx.execute(s -> {
            idempotency.record(merchantId, op, key, hash, paymentId, status);
            return new PaymentResult(lookup.require(paymentId), status, false);
        });
    }

    private PaymentAttempt authorizedAttempt(UUID paymentId) {
        return attempts.findByPaymentIdOrderByCreatedAtAsc(paymentId).stream()
                .filter(a -> a.getStatus() == AttemptStatus.AUTHORIZED)
                .max(Comparator.comparing(PaymentAttempt::getCreatedAt))
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.INVALID_STATE_TRANSITION,
                        "No authorized attempt found for this payment."));
    }
}
