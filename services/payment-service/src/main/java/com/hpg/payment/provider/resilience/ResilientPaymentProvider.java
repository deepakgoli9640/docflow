package com.hpg.payment.provider.resilience;

import com.hpg.common.money.Money;
import com.hpg.payment.provider.CheckoutConfig;
import com.hpg.payment.provider.CheckoutRequest;
import com.hpg.payment.provider.CreateOrderCommand;
import com.hpg.payment.provider.PaymentProvider;
import com.hpg.payment.provider.ProviderException;
import com.hpg.payment.provider.ProviderOrder;
import com.hpg.payment.provider.ProviderDispute;
import com.hpg.payment.provider.ProviderPayment;
import com.hpg.payment.provider.ProviderRejectedException;
import com.hpg.payment.provider.ProviderTimeoutException;
import com.hpg.payment.provider.ProviderUnavailableException;
import com.hpg.payment.provider.ProviderWebhookEvent;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Decorator that adds resilience to any {@link PaymentProvider} (open/closed principle: adapters stay
 * free of resilience code). Call chain: Retry → CircuitBreaker → Bulkhead → adapter.
 *
 * <ul>
 *   <li><b>Retry</b>: two policies. {@code safeRetry} retries only failures where the request certainly
 *       did not take effect; {@code idempotentRetry} also retries timeouts and is used only for operations
 *       that are harmless to repeat (reads, order creation). Capture uses {@code safeRetry}: a timed-out
 *       capture is never blindly repeated — it becomes UNKNOWN and is verified.</li>
 *   <li><b>CircuitBreaker</b>: shared by all lanes (the provider is equally healthy or sick for all).
 *       Rejections (4xx) do not count as failures — they mean the provider is up.</li>
 *   <li><b>Bulkhead</b>: one per lane, so a backlog of recovery polls can never use up the concurrency
 *       that patients at the checkout need.</li>
 * </ul>
 * Signature checks and parsing are local computations and are not decorated.
 */
public class ResilientPaymentProvider implements PaymentProvider {

    private final PaymentProvider delegate;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;
    private final Retry safeRetry;
    private final Retry idempotentRetry;
    private final MeterRegistry meters;
    private final String lane;

    public ResilientPaymentProvider(PaymentProvider delegate, CircuitBreaker circuitBreaker, Bulkhead bulkhead,
            Retry safeRetry, Retry idempotentRetry, MeterRegistry meters, String lane) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
        this.bulkhead = bulkhead;
        this.safeRetry = safeRetry;
        this.idempotentRetry = idempotentRetry;
        this.meters = meters;
        this.lane = lane;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public ProviderOrder createOrder(CreateOrderCommand command) {
        return execute("createOrder", idempotentRetry, () -> delegate.createOrder(command));
    }

    @Override
    public List<ProviderPayment> fetchOrderPayments(String providerOrderId) {
        return execute("fetchOrderPayments", idempotentRetry, () -> delegate.fetchOrderPayments(providerOrderId));
    }

    @Override
    public ProviderPayment fetchPayment(String providerPaymentId) {
        return execute("fetchPayment", idempotentRetry, () -> delegate.fetchPayment(providerPaymentId));
    }

    @Override
    public ProviderPayment capture(String providerPaymentId, Money amount) {
        return execute("capture", safeRetry, () -> delegate.capture(providerPaymentId, amount));
    }

    @Override
    public ProviderDispute acceptDispute(String providerDisputeId) {
        return execute("acceptDispute", safeRetry, () -> delegate.acceptDispute(providerDisputeId));
    }

    @Override
    public ProviderDispute contestDispute(String providerDisputeId, Money amount, String summary) {
        return execute("contestDispute", safeRetry, () -> delegate.contestDispute(providerDisputeId, amount, summary));
    }

    @Override
    public boolean verifyCheckoutSignature(String providerOrderId, String providerPaymentId, String signature) {
        return delegate.verifyCheckoutSignature(providerOrderId, providerPaymentId, signature);
    }

    @Override
    public boolean verifyWebhookSignature(byte[] rawBody, String signature) {
        return delegate.verifyWebhookSignature(rawBody, signature);
    }

    @Override
    public Optional<ProviderWebhookEvent> parseWebhook(String eventId, byte[] rawBody) {
        return delegate.parseWebhook(eventId, rawBody);
    }

    @Override
    public CheckoutConfig checkoutConfig(CheckoutRequest request) {
        return delegate.checkoutConfig(request);
    }

    private <T> T execute(String operation, Retry retry, Supplier<T> call) {
        Supplier<T> guarded = Bulkhead.decorateSupplier(bulkhead, call);
        guarded = CircuitBreaker.decorateSupplier(circuitBreaker, guarded);
        guarded = Retry.decorateSupplier(retry, guarded);
        Timer.Sample sample = Timer.start(meters);
        String outcome = "success";
        try {
            return guarded.get();
        } catch (CallNotPermittedException e) {
            outcome = "circuit_open";
            throw new ProviderUnavailableException(operation, "circuit breaker open", e);
        } catch (BulkheadFullException e) {
            outcome = "bulkhead_full";
            throw new ProviderUnavailableException(operation, "too many concurrent provider calls (" + lane + ")", e);
        } catch (ProviderRejectedException e) {
            outcome = "rejected";
            throw e;
        } catch (ProviderTimeoutException e) {
            outcome = "timeout";
            throw e;
        } catch (ProviderUnavailableException e) {
            outcome = "unavailable";
            throw e;
        } catch (ProviderException e) {
            outcome = "error";
            throw e;
        } finally {
            sample.stop(Timer.builder("provider.requests")
                    .description("Calls to the external payment provider")
                    .tag("provider", delegate.name()).tag("operation", operation)
                    .tag("lane", lane).tag("outcome", outcome)
                    .register(meters));
        }
    }
}
