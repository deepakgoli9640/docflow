package com.hpg.refund.provider.simulated;

import com.hpg.common.crypto.SecureTokens;
import com.hpg.common.money.Money;
import com.hpg.refund.provider.ProviderRefund;
import com.hpg.refund.provider.ProviderRejectedException;
import com.hpg.refund.provider.RefundProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>SIMULATED</b> refund provider for local demos without Razorpay credentials. In memory, single
 * instance, no money moves. Refunds are reported as processed immediately.
 */
public class SimulatedRefundProvider implements RefundProvider {

    public static final String NAME = "SIMULATED";
    private static final Logger log = LoggerFactory.getLogger(SimulatedRefundProvider.class);

    private final Map<String, ProviderRefund> refunds = new ConcurrentHashMap<>();

    public SimulatedRefundProvider() {
        log.warn("*** SIMULATED REFUND PROVIDER ACTIVE: no real refunds are processed. Local development only. ***");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ProviderRefund createRefund(String providerPaymentId, Money amount, UUID refundId) {
        String id = "rfnd_sim_" + SecureTokens.alphanumeric(14);
        ProviderRefund refund = new ProviderRefund(id, providerPaymentId, amount, ProviderRefund.Status.PROCESSED,
                refundId.toString(), null, null);
        refunds.put(id, refund);
        log.info("SIMULATED provider: refund {} of {} on {}", id, amount, providerPaymentId);
        return refund;
    }

    @Override
    public List<ProviderRefund> fetchRefunds(String providerPaymentId) {
        return refunds.values().stream().filter(r -> providerPaymentId.equals(r.providerPaymentId())).toList();
    }

    @Override
    public ProviderRefund fetchRefund(String providerPaymentId, String providerRefundId) {
        ProviderRefund refund = refunds.get(providerRefundId);
        if (refund == null) {
            throw new ProviderRejectedException("fetchRefund", "BAD_REQUEST_ERROR", "The id provided does not exist");
        }
        return refund;
    }

    @Override
    public boolean verifyWebhookSignature(byte[] rawBody, String signature) {
        return false; // the simulator sends no webhooks
    }

    @Override
    public Optional<ProviderRefund> parseWebhook(byte[] rawBody) {
        return Optional.empty();
    }
}
