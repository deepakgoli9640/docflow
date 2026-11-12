package com.hpg.payment.provider;

import com.hpg.common.money.Money;

import java.util.List;
import java.util.Optional;

/**
 * Port to an external payment provider. The rest of payment-service depends only on this interface,
 * never on Razorpay classes, so a second provider means a second adapter, not a rewrite.
 *
 * <p>Error contract (all unchecked, see {@link ProviderException}):
 * <ul>
 *   <li>{@link ProviderUnavailableException} — the request certainly did not take effect; safe to retry.</li>
 *   <li>{@link ProviderTimeoutException} — the request may or may not have taken effect; only
 *       idempotent operations may be retried, others must be verified first.</li>
 *   <li>{@link ProviderRejectedException} — the provider said no; retrying the same request will not help.</li>
 *   <li>{@link ProviderAuthenticationException} — our credentials/configuration are wrong; alert.</li>
 * </ul>
 */
public interface PaymentProvider {

    /** Stable provider name stored with orders, e.g. {@code RAZORPAY}. */
    String name();

    /** Creates a provider order. Safe to repeat: an order holds no money, a duplicate is harmless. */
    ProviderOrder createOrder(CreateOrderCommand command);

    /** All payment attempts the provider knows for an order. Read-only. */
    List<ProviderPayment> fetchOrderPayments(String providerOrderId);

    /** One payment attempt. Read-only. */
    ProviderPayment fetchPayment(String providerPaymentId);

    /**
     * Captures an authorized payment. Idempotent at the provider: capturing an already captured payment
     * returns the captured payment rather than charging twice.
     */
    ProviderPayment capture(String providerPaymentId, Money amount);

    /** Verifies the signature the provider's checkout returns to the browser. */
    boolean verifyCheckoutSignature(String providerOrderId, String providerPaymentId, String signature);

    /** Verifies a webhook's signature over the exact raw request body. */
    boolean verifyWebhookSignature(byte[] rawBody, String signature);

    /** Parses a webhook whose signature has already been verified. Empty if it carries no payment. */
    Optional<ProviderWebhookEvent> parseWebhook(String eventId, byte[] rawBody);

    /** What the hosted checkout page needs to open the provider's checkout for this order. */
    CheckoutConfig checkoutConfig(CheckoutRequest request);
}
