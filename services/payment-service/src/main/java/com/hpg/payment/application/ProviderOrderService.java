package com.hpg.payment.application;

import com.hpg.payment.domain.Payment;
import com.hpg.payment.domain.PaymentOrder;
import com.hpg.payment.domain.PaymentStatus;
import com.hpg.payment.domain.TransitionSource;
import com.hpg.payment.infrastructure.persistence.PaymentOrderRepository;
import com.hpg.payment.provider.CreateOrderCommand;
import com.hpg.payment.provider.PaymentProvider;
import com.hpg.payment.provider.ProviderAuthenticationException;
import com.hpg.payment.provider.ProviderOrder;
import com.hpg.payment.provider.ProviderRejectedException;
import com.hpg.payment.provider.ProviderTimeoutException;
import com.hpg.payment.provider.ProviderUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Creates the provider order for a CREATED payment. The provider call happens OUTSIDE any database
 * transaction: holding a connection and row locks while waiting seconds on the network would turn a
 * slow provider into an exhausted connection pool.
 *
 * <p>Provider unavailable or timed out: the payment simply stays CREATED and is retried later (by the
 * checkout page on first load or the recovery job). Repeating order creation is safe — an order holds no
 * money, and an order whose id we never learned can never be shown to a patient.
 */
@Service
public class ProviderOrderService {

    private static final Logger log = LoggerFactory.getLogger(ProviderOrderService.class);

    private final PaymentLookup lookup;
    private final PaymentOrderRepository orders;
    private final PaymentTransitions transitions;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ProviderOrderService(PaymentLookup lookup, PaymentOrderRepository orders, PaymentTransitions transitions,
            TransactionTemplate tx, Clock clock) {
        this.lookup = lookup;
        this.orders = orders;
        this.transitions = transitions;
        this.tx = tx;
        this.clock = clock;
    }

    /** @return the provider order, if the payment has (or now got) one */
    public Optional<PaymentOrder> ensureOrder(UUID paymentId, PaymentProvider provider) {
        Payment payment = lookup.require(paymentId);
        Optional<PaymentOrder> existing = orders.findFirstByPaymentIdOrderByCreatedAtDesc(paymentId);
        if (existing.isPresent() || payment.getStatus() != PaymentStatus.CREATED) {
            return existing;
        }
        ProviderOrder created;
        try {
            created = provider.createOrder(new CreateOrderCommand(paymentId, paymentId.toString(), payment.amount(),
                    payment.getCaptureMode(), Map.of("payment_id", paymentId.toString(),
                            "merchant_id", payment.getMerchantId().toString())));
        } catch (ProviderRejectedException e) {
            log.warn("Provider rejected order for paymentId={}: {}", paymentId, e.getMessage());
            OptimisticRetry.run("order-rejected", () -> tx.execute(status -> {
                Payment p = lookup.require(paymentId);
                p.recordFailure(e.providerErrorCode(), e.providerDescription());
                transitions.apply(p, PaymentStatus.FAILED, TransitionSource.PROVIDER_RESPONSE,
                        "provider rejected order", null, null);
                return null;
            }));
            return Optional.empty();
        } catch (ProviderUnavailableException | ProviderTimeoutException | ProviderAuthenticationException e) {
            // Not swallowed: logged with the reason, and the payment stays CREATED for a later retry.
            log.warn("Could not create provider order for paymentId={} ({}); will retry later", paymentId, e.getMessage());
            return Optional.empty();
        }
        return Optional.of(OptimisticRetry.run("order-created", () -> tx.execute(status -> {
            // Another request may have created an order meanwhile (checkout page and recovery job racing):
            // keep the first one we recorded; the duplicate provider order is harmless.
            Optional<PaymentOrder> raced = orders.findFirstByPaymentIdOrderByCreatedAtDesc(paymentId);
            if (raced.isPresent()) {
                return raced.get();
            }
            Payment p = lookup.require(paymentId);
            PaymentOrder order = orders.save(new PaymentOrder(paymentId, provider.name(), created.providerOrderId(),
                    payment.amount(), clock.instant()));
            transitions.apply(p, PaymentStatus.PENDING, TransitionSource.PROVIDER_RESPONSE, "provider order created",
                    created.providerOrderId(), null);
            return order;
        })));
    }
}
