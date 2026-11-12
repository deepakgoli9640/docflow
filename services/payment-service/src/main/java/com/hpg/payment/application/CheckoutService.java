package com.hpg.payment.application;

import com.hpg.common.error.BusinessException;
import com.hpg.common.error.CommonErrorCode;
import com.hpg.common.error.NotFoundException;
import com.hpg.payment.domain.MerchantConfigReplica;
import com.hpg.payment.domain.Payment;
import com.hpg.payment.domain.PaymentAttempt;
import com.hpg.payment.domain.PaymentOrder;
import com.hpg.payment.domain.PaymentStatus;
import com.hpg.payment.domain.TransitionSource;
import com.hpg.payment.infrastructure.persistence.MerchantConfigReplicaRepository;
import com.hpg.payment.infrastructure.persistence.PaymentAttemptRepository;
import com.hpg.payment.infrastructure.persistence.PaymentOrderRepository;
import com.hpg.payment.infrastructure.persistence.PaymentRepository;
import com.hpg.payment.provider.CheckoutConfig;
import com.hpg.payment.provider.CheckoutRequest;
import com.hpg.payment.provider.PaymentProvider;
import com.hpg.payment.provider.ProviderException;
import com.hpg.payment.provider.ProviderPayment;
import com.hpg.payment.provider.resilience.PaymentProviders;
import com.hpg.payment.provider.simulated.SimulatedPaymentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Optional;
import java.util.Set;

/**
 * The patient-facing part: hosted checkout page and the browser callback.
 *
 * <p>The callback is the first of three independent signals (callback, webhook, polling). Its signature
 * proves the provider produced it, but the browser may never deliver it — so after accepting it we
 * immediately ask the provider for the authoritative status, and webhooks/polling cover the case where
 * the callback never arrives at all.
 */
@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    private final PaymentRepository payments;
    private final PaymentOrderRepository orders;
    private final PaymentAttemptRepository attempts;
    private final MerchantConfigReplicaRepository merchants;
    private final ProviderOrderService providerOrders;
    private final PaymentEvidenceService evidence;
    private final PaymentTransitions transitions;
    private final PaymentProviders providers;
    private final ObjectProvider<SimulatedPaymentProvider> simulator;
    private final PaymentLookup lookup;
    private final PaymentProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    public CheckoutService(PaymentRepository payments, PaymentOrderRepository orders, PaymentAttemptRepository attempts,
            MerchantConfigReplicaRepository merchants, ProviderOrderService providerOrders,
            PaymentEvidenceService evidence, PaymentTransitions transitions, PaymentProviders providers,
            ObjectProvider<SimulatedPaymentProvider> simulator, PaymentLookup lookup, PaymentProperties properties,
            TransactionTemplate tx, Clock clock) {
        this.payments = payments;
        this.orders = orders;
        this.attempts = attempts;
        this.merchants = merchants;
        this.providerOrders = providerOrders;
        this.evidence = evidence;
        this.transitions = transitions;
        this.providers = providers;
        this.simulator = simulator;
        this.lookup = lookup;
        this.properties = properties;
        this.tx = tx;
        this.clock = clock;
    }

    public sealed interface CheckoutView permits Payable, Finished, Unavailable {
    }

    /** The patient can pay: render the provider's checkout. */
    public record Payable(Payment payment, String merchantName, CheckoutConfig config, String callbackUrl)
            implements CheckoutView {
    }

    /** Paid, failed, cancelled or expired: show the outcome instead of a pay button. */
    public record Finished(Payment payment, String merchantName) implements CheckoutView {
    }

    /** Payable in principle, but the provider cannot be reached right now. */
    public record Unavailable(Payment payment, String merchantName) implements CheckoutView {
    }

    public CheckoutView open(String checkoutToken) {
        Payment payment = byToken(checkoutToken);
        String merchantName = merchantName(payment);
        if (!payment.isPayableAt(clock.instant())) {
            return new Finished(payment, merchantName);
        }
        // Lazy order creation: if the provider was down when the hospital created the payment, try now.
        Optional<PaymentOrder> order = providerOrders.ensureOrder(payment.getId(), providers.live());
        if (order.isEmpty()) {
            Payment current = lookup.require(payment.getId());
            return current.isPayableAt(clock.instant()) ? new Unavailable(current, merchantName)
                    : new Finished(current, merchantName);
        }
        Payment current = lookup.require(payment.getId());
        Payment.Customer customer = current.customer();
        Set<String> methods = merchants.findById(current.getMerchantId())
                .map(MerchantConfigReplica::getEnabledMethods).orElse(Set.of());
        String callbackUrl = properties.checkoutBaseUrl() + "/" + checkoutToken + "/callback";
        CheckoutConfig config = providers.live().checkoutConfig(new CheckoutRequest(order.get().getProviderOrderId(),
                current.amount(), merchantName, current.getDescription(),
                customer == null ? null : customer.name(), customer == null ? null : customer.email(),
                customer == null ? null : customer.phone(), methods, callbackUrl));
        return new Payable(current, merchantName, config, callbackUrl);
    }

    /**
     * Handles the signed callback the provider's checkout gives the browser after a successful payment.
     *
     * @throws BusinessException 400 if the signature or the order does not match this checkout
     */
    public Payment handleCallback(String checkoutToken, String providerOrderId, String providerPaymentId,
            String signature) {
        Payment payment = byToken(checkoutToken);
        PaymentProvider provider = providers.live();
        PaymentOrder order = orders.findByProviderAndProviderOrderId(provider.name(), providerOrderId)
                .filter(o -> o.getPaymentId().equals(payment.getId()))
                .orElseThrow(() -> invalidCallback("order does not belong to this checkout"));
        if (providerPaymentId == null || providerPaymentId.isBlank()
                || !provider.verifyCheckoutSignature(providerOrderId, providerPaymentId, signature)) {
            log.warn("Rejected checkout callback with invalid signature paymentId={}", payment.getId());
            throw invalidCallback("signature verification failed");
        }

        OptimisticRetry.run("checkout-callback", () -> tx.execute(status -> {
            Payment p = lookup.require(payment.getId());
            if (attempts.findByProviderPaymentId(providerPaymentId).isEmpty()) {
                attempts.save(new PaymentAttempt(p.getId(), order.getId(), providerPaymentId, null, p.amount()));
            }
            transitions.apply(p, PaymentStatus.PROCESSING, TransitionSource.CHECKOUT_CALLBACK,
                    "signed checkout callback received", providerPaymentId, providerPaymentId);
            return null;
        }));

        // Do not trust the browser for the outcome: ask the provider now. If that fails, the payment stays
        // PROCESSING and the webhook or the recovery job completes it.
        try {
            ProviderPayment truth = provider.fetchPayment(providerPaymentId);
            OptimisticRetry.run("checkout-verify", () -> tx.execute(status -> {
                Payment p = lookup.require(payment.getId());
                evidence.apply(p, order, truth, TransitionSource.PROVIDER_POLL, providerPaymentId);
                p.markVerified(clock.instant());
                return null;
            }));
        } catch (ProviderException e) {
            log.warn("Immediate verification failed for paymentId={} ({}); webhook or recovery job will complete it",
                    payment.getId(), e.getMessage());
        }
        return lookup.require(payment.getId());
    }

    /**
     * SIMULATED provider only: what the patient's action on the provider's page would cause.
     * {@code deliverCallback=false} reproduces "the provider charged the patient but our callback was lost".
     */
    public Payment simulate(String checkoutToken, SimulatedPaymentProvider.Outcome outcome, boolean deliverCallback) {
        SimulatedPaymentProvider sim = simulator.getIfAvailable();
        if (sim == null) {
            throw new NotFoundException("Simulation is only available with the simulated provider.");
        }
        Payment payment = byToken(checkoutToken);
        PaymentOrder order = providerOrders.ensureOrder(payment.getId(), providers.live())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, "No provider order."));
        SimulatedPaymentProvider.SimulatedCheckoutResult result = sim.simulatePayment(order.getProviderOrderId(), outcome);
        if (outcome == SimulatedPaymentProvider.Outcome.FAILURE) {
            // A failed attempt produces no callback; the provider would report it via webhook/poll.
            OptimisticRetry.run("simulated-failure", () -> tx.execute(status -> {
                Payment p = lookup.require(payment.getId());
                evidence.apply(p, order, sim.fetchPayment(result.providerPaymentId()), TransitionSource.PROVIDER_POLL,
                        result.providerPaymentId());
                return null;
            }));
            return lookup.require(payment.getId());
        }
        if (!deliverCallback) {
            log.warn("SIMULATED: payment {} succeeded at the provider but the callback is deliberately dropped",
                    result.providerPaymentId());
            return lookup.require(payment.getId());
        }
        return handleCallback(checkoutToken, result.providerOrderId(), result.providerPaymentId(), result.signature());
    }

    public boolean simulationEnabled() {
        return simulator.getIfAvailable() != null;
    }

    private Payment byToken(String checkoutToken) {
        return payments.findByCheckoutToken(checkoutToken)
                .orElseThrow(() -> new NotFoundException("This payment link is not valid."));
    }

    private String merchantName(Payment payment) {
        return merchants.findById(payment.getMerchantId()).map(MerchantConfigReplica::getDisplayName).orElse("Hospital");
    }

    private static BusinessException invalidCallback(String why) {
        return new BusinessException(CommonErrorCode.VALIDATION_FAILED, "Invalid payment callback: " + why + ".");
    }
}
