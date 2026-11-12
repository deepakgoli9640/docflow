package com.hpg.payment.application;

import com.hpg.payment.domain.Payment;
import com.hpg.payment.domain.PaymentOrder;
import com.hpg.payment.domain.PaymentStatus;
import com.hpg.payment.domain.TransitionSource;
import com.hpg.payment.infrastructure.persistence.PaymentOrderRepository;
import com.hpg.payment.infrastructure.persistence.PaymentRepository;
import com.hpg.payment.provider.ProviderException;
import com.hpg.payment.provider.ProviderPayment;
import com.hpg.payment.provider.resilience.PaymentProviders;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Recovery and expiry (docs/02-payment-lifecycle.md §8). Every action asks the provider first and
 * converges our state to the provider's truth. All provider calls use the recovery lane, whose bulkhead
 * is separate from live checkout traffic. Each payment is processed in its own transaction; a failure
 * on one payment is logged and counted and does not stop the batch.
 */
@Service
public class PaymentRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(PaymentRecoveryService.class);

    private final PaymentRepository payments;
    private final PaymentOrderRepository orders;
    private final ProviderOrderService providerOrders;
    private final PaymentEvidenceService evidence;
    private final PaymentTransitions transitions;
    private final PaymentLookup lookup;
    private final PaymentProviders providers;
    private final PaymentProperties properties;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;
    private final Clock clock;

    public PaymentRecoveryService(PaymentRepository payments, PaymentOrderRepository orders,
            ProviderOrderService providerOrders, PaymentEvidenceService evidence, PaymentTransitions transitions,
            PaymentLookup lookup, PaymentProviders providers, PaymentProperties properties, TransactionTemplate tx,
            MeterRegistry meters, Clock clock) {
        this.payments = payments;
        this.orders = orders;
        this.providerOrders = providerOrders;
        this.evidence = evidence;
        this.transitions = transitions;
        this.lookup = lookup;
        this.providers = providers;
        this.properties = properties;
        this.tx = tx;
        this.meters = meters;
        this.clock = clock;
    }

    /** CREATED payments whose provider order was never created (provider was down at creation time). */
    public int retryOrderCreation() {
        Instant now = clock.instant();
        List<UUID> ids = payments.findIdsByStatusUpdatedBefore(PaymentStatus.CREATED,
                now.minus(properties.jobs().createdRetryAfter()), page());
        return forEach("order-creation", ids, id -> {
            if (lookup.require(id).getExpiresAt().isAfter(now)) {
                providerOrders.ensureOrder(id, providers.recovery());
            }
        });
    }

    /**
     * PROCESSING (callback received, no confirmation), UNKNOWN (capture timed out) and long-PENDING payments
     * (callback and webhook both lost) are checked against the provider.
     */
    public int verifyInFlight() {
        Instant now = clock.instant();
        var jobs = properties.jobs();
        List<UUID> ids = new ArrayList<>();
        ids.addAll(payments.findIdsByStatusUpdatedBefore(PaymentStatus.PROCESSING, now.minus(jobs.processingVerifyAfter()), page()));
        ids.addAll(payments.findIdsByStatusUpdatedBefore(PaymentStatus.UNKNOWN, now.minus(jobs.unknownVerifyAfter()), page()));
        ids.addAll(payments.findPendingIdsToVerify(now.minus(jobs.pendingVerifyInterval()), now, page()));
        return forEach("verify", ids, this::verify);
    }

    /**
     * Expires CREATED/PENDING payments past their deadline — but only after asking the provider: a patient
     * may have paid seconds before the deadline. If the provider cannot be reached, the payment is left
     * as it is and retried on the next run.
     */
    public int expirePayments() {
        List<UUID> ids = payments.findExpiredIds(EnumSet.of(PaymentStatus.CREATED, PaymentStatus.PENDING),
                clock.instant(), page());
        return forEach("expiry", ids, id -> {
            verify(id);
            OptimisticRetry.run("expire", () -> tx.execute(status -> {
                Payment p = lookup.require(id);
                if (PaymentStatus.PAYABLE.contains(p.getStatus())) {
                    transitions.apply(p, PaymentStatus.EXPIRED, TransitionSource.SYSTEM,
                            "payment link expired; provider confirms no successful payment", null, null);
                }
                return null;
            }));
        });
    }

    /** AUTHORIZED payments not captured within the provider's capture window (the provider reverses them). */
    public int expireAuthorizations() {
        Instant before = clock.instant().minus(properties.captureWindow());
        return forEach("authorization-expiry", payments.findAuthorizedIdsBefore(before, page()), id -> {
            verify(id);
            OptimisticRetry.run("expire-authorization", () -> tx.execute(status -> {
                Payment p = lookup.require(id);
                if (p.getStatus() == PaymentStatus.AUTHORIZED) {
                    transitions.apply(p, PaymentStatus.EXPIRED, TransitionSource.SYSTEM,
                            "authorization not captured within the capture window", null, null);
                }
                return null;
            }));
        });
    }

    /** Fetches the provider's view of the payment's order and applies it. Throws if the provider is unreachable. */
    public void verify(UUID paymentId) {
        Optional<PaymentOrder> order = orders.findFirstByPaymentIdOrderByCreatedAtDesc(paymentId);
        if (order.isEmpty()) {
            return;
        }
        List<ProviderPayment> found = providers.recovery().fetchOrderPayments(order.get().getProviderOrderId());
        OptimisticRetry.run("verify", () -> tx.execute(status -> {
            Payment p = lookup.require(paymentId);
            evidence.applyAll(p, order.get(), found, TransitionSource.PROVIDER_POLL, "poll:" + paymentId);
            p.markVerified(clock.instant());
            return null;
        }));
    }

    private int forEach(String job, List<UUID> ids, Consumer<UUID> action) {
        int done = 0;
        for (UUID id : ids.stream().distinct().toList()) {
            try {
                action.accept(id);
                done++;
            } catch (ProviderException e) {
                log.warn("Recovery job {} could not reach the provider for paymentId={}: {}", job, id, e.getMessage());
                meters.counter("payments.recovery.errors", "job", job, "kind", "provider").increment();
            } catch (RuntimeException e) {
                log.error("Recovery job {} failed for paymentId={}", job, id, e);
                meters.counter("payments.recovery.errors", "job", job, "kind", "unexpected").increment();
            }
        }
        return done;
    }

    private PageRequest page() {
        return PageRequest.of(0, properties.jobs().batchSize());
    }
}
