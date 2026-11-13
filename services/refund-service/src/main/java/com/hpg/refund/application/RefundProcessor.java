package com.hpg.refund.application;

import com.hpg.refund.domain.Refund;
import com.hpg.refund.domain.RefundStatus;
import com.hpg.refund.domain.RefundablePayment;
import com.hpg.refund.infrastructure.persistence.RefundRepository;
import com.hpg.refund.infrastructure.persistence.RefundablePaymentStore;
import com.hpg.refund.provider.ProviderRefund;
import com.hpg.refund.provider.ProviderRejectedException;
import com.hpg.refund.provider.ProviderTimeoutException;
import com.hpg.refund.provider.ProviderUnavailableException;
import com.hpg.refund.provider.RefundProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Talks to the provider for a refund and applies what it learns. Provider calls always happen outside
 * database transactions; each outcome is applied in its own short transaction with optimistic locking
 * (a webhook and an API response for the same refund may race).
 */
@Service
public class RefundProcessor {

    private static final Logger log = LoggerFactory.getLogger(RefundProcessor.class);
    private static final int OPTIMISTIC_RETRIES = 3;

    private final RefundRepository refunds;
    private final RefundablePaymentStore refundables;
    private final RefundTransitions transitions;
    private final RefundProvider provider;
    private final TransactionTemplate tx;
    private final Clock clock;

    public RefundProcessor(RefundRepository refunds, RefundablePaymentStore refundables, RefundTransitions transitions,
            RefundProvider provider, PlatformTransactionManager txManager, Clock clock) {
        this.refunds = refunds;
        this.refundables = refundables;
        this.transitions = transitions;
        this.provider = provider;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /**
     * Sends a CREATED refund to the provider. The attempt is claimed first (version bump): if two callers
     * race, one loses on the optimistic lock and does not call the provider.
     *
     * @return the refund after applying the provider's answer
     */
    public Refund submit(UUID refundId) {
        Refund claimed;
        try {
            claimed = tx.execute(s -> {
                Refund r = refunds.findById(refundId).orElseThrow();
                if (r.getStatus() != RefundStatus.CREATED) {
                    return null;
                }
                r.recordProviderAttempt(clock.instant());
                return refunds.saveAndFlush(r);
            });
        } catch (OptimisticLockingFailureException e) {
            log.info("Refund {} is being submitted by another worker", refundId);
            return refunds.findById(refundId).orElseThrow();
        }
        if (claimed == null) {
            return refunds.findById(refundId).orElseThrow();
        }
        RefundablePayment payment = refundables.find(claimed.getPaymentId()).orElseThrow();
        try {
            ProviderRefund result = provider.createRefund(payment.providerPaymentId(), claimed.amount(), refundId);
            return applyEvidence(refundId, result, "provider-response");
        } catch (ProviderRejectedException e) {
            log.warn("Provider rejected refund {}: {} {}", refundId, e.code(), e.description());
            return inTx(refundId, r -> {
                r.recordFailure(e.code(), e.description());
                transitions.apply(r, RefundStatus.FAILED, "provider rejected: " + e.code(), "provider-response");
            });
        } catch (ProviderTimeoutException e) {
            // The provider may have created it. Never resend blindly: recovery looks it up first.
            log.warn("Refund {} outcome unknown: {}", refundId, e.getMessage());
            return inTx(refundId, r -> transitions.apply(r, RefundStatus.UNKNOWN, e.getMessage(), "provider-timeout"));
        } catch (ProviderUnavailableException e) {
            // Certainly not applied: stays CREATED; recovery retries after a lookup.
            log.warn("Provider unavailable for refund {}: {}", refundId, e.getMessage());
            return refunds.findById(refundId).orElseThrow();
        }
    }

    /** Applies provider evidence (API response, webhook or poll) to a refund. */
    public Refund applyEvidence(UUID refundId, ProviderRefund evidence, String causationId) {
        RefundStatus target = switch (evidence.status()) {
            case PENDING -> RefundStatus.PROCESSING;
            case PROCESSED -> RefundStatus.COMPLETED;
            case FAILED -> RefundStatus.FAILED;
        };
        return inTx(refundId, r -> {
            r.adoptProviderRefundId(evidence.providerRefundId());
            if (target == RefundStatus.FAILED) {
                r.recordFailure(evidence.errorCode() == null ? "PROVIDER_FAILED" : evidence.errorCode(),
                        evidence.errorDescription() == null ? "Refund failed at the provider" : evidence.errorDescription());
            }
            transitions.apply(r, target, "provider reports " + evidence.status(), causationId);
        });
    }

    /** Moves an UNKNOWN refund back to CREATED after a lookup proved the provider never created it. */
    public Refund resetForRetry(UUID refundId) {
        return inTx(refundId, r -> transitions.apply(r, RefundStatus.CREATED, "not found at provider", "recovery"));
    }

    public Refund giveUp(UUID refundId, String reason) {
        return inTx(refundId, r -> {
            r.recordFailure("MAX_ATTEMPTS", reason);
            transitions.apply(r, RefundStatus.FAILED, reason, "recovery");
        });
    }

    private Refund inTx(UUID refundId, java.util.function.Consumer<Refund> change) {
        return withOptimisticRetry(() -> tx.execute(s -> {
            Refund r = refunds.findById(refundId).orElseThrow();
            change.accept(r);
            return refunds.saveAndFlush(r);
        }));
    }

    private static Refund withOptimisticRetry(Supplier<Refund> action) {
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (OptimisticLockingFailureException e) {
                if (attempt >= OPTIMISTIC_RETRIES) {
                    throw e;
                }
            }
        }
    }
}
