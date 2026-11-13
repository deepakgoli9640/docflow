package com.hpg.refund.provider;

import com.hpg.common.money.Money;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port to the provider's refund API. Error contract, as in payment-service:
 * {@link ProviderUnavailableException} = certainly not applied (safe to retry);
 * {@link ProviderTimeoutException} = may have been applied (look it up before any retry);
 * {@link ProviderRejectedException} = the provider said no.
 */
public interface RefundProvider {

    String name();

    /** Creates a refund. NOT idempotent at the provider: never resend after a timeout without a lookup. */
    ProviderRefund createRefund(String providerPaymentId, Money amount, UUID refundId);

    /** All refunds of a payment. Read-only; used to find ours by its id after a timeout. */
    List<ProviderRefund> fetchRefunds(String providerPaymentId);

    /** One refund. Read-only. */
    ProviderRefund fetchRefund(String providerPaymentId, String providerRefundId);

    boolean verifyWebhookSignature(byte[] rawBody, String signature);

    /** @return the refund carried by a refund webhook; empty for other event types */
    Optional<ProviderRefund> parseWebhook(byte[] rawBody);

    /** When the provider created the webhook event, if it says (replay protection). */
    default java.time.Instant webhookCreatedAt(byte[] rawBody) {
        return null;
    }
}
