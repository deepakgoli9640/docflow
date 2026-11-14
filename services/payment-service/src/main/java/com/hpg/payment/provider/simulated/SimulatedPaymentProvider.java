package com.hpg.payment.provider.simulated;

import com.hpg.common.crypto.SecureTokens;
import com.hpg.common.money.Money;
import com.hpg.payment.domain.CaptureMode;
import com.hpg.payment.provider.CheckoutConfig;
import com.hpg.payment.provider.CheckoutRequest;
import com.hpg.payment.provider.CreateOrderCommand;
import com.hpg.payment.provider.HmacSignatures;
import com.hpg.payment.provider.PaymentProvider;
import com.hpg.payment.provider.ProviderDispute;
import com.hpg.payment.provider.ProviderOrder;
import com.hpg.payment.provider.ProviderPayment;
import com.hpg.payment.provider.ProviderPaymentStatus;
import com.hpg.payment.provider.ProviderRejectedException;
import com.hpg.payment.provider.ProviderWebhookEvent;
import com.hpg.payment.provider.razorpay.RazorpayDisputes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>SIMULATED</b> payment provider for local demos without Razorpay credentials.
 *
 * <p>Active only with {@code hpg.payment.provider=simulated}. Everything is in memory (lost on restart,
 * single instance only) and no money moves. It exists so the full flow — order, checkout, callback,
 * capture, recovery polling — can be exercised end to end on a laptop. Automated tests of the real
 * adapter use WireMock instead. Never enable this outside local development.
 */
public class SimulatedPaymentProvider implements PaymentProvider {

    public static final String NAME = "SIMULATED";
    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentProvider.class);
    /** Not a secret: signatures exist only so the simulated flow uses the same verification code path. */
    private static final String SIGNING_KEY = "simulated-provider-signing-key";

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private final Map<String, SimOrder> orders = new ConcurrentHashMap<>();
    private final Map<String, ProviderDispute> disputes = new ConcurrentHashMap<>();
    private final Map<String, ProviderPayment> payments = new ConcurrentHashMap<>();

    public SimulatedPaymentProvider() {
        log.warn("*** SIMULATED PAYMENT PROVIDER ACTIVE: no real payments are processed. Local development only. ***");
    }

    public enum Outcome { SUCCESS, FAILURE }

    public record SimulatedCheckoutResult(String providerOrderId, String providerPaymentId, String signature) {
    }

    private record SimOrder(String id, Money amount, CaptureMode captureMode) {
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ProviderOrder createOrder(CreateOrderCommand command) {
        String id = "order_sim_" + SecureTokens.alphanumeric(14);
        orders.put(id, new SimOrder(id, command.amount(), command.captureMode()));
        log.info("SIMULATED provider: created order {}", id);
        return new ProviderOrder(id, command.amount(), "created");
    }

    @Override
    public List<ProviderPayment> fetchOrderPayments(String providerOrderId) {
        return payments.values().stream().filter(p -> providerOrderId.equals(p.providerOrderId())).toList();
    }

    @Override
    public ProviderPayment fetchPayment(String providerPaymentId) {
        ProviderPayment payment = payments.get(providerPaymentId);
        if (payment == null) {
            throw new ProviderRejectedException("fetchPayment", "BAD_REQUEST_ERROR", "The id provided does not exist");
        }
        return payment;
    }

    @Override
    public ProviderPayment capture(String providerPaymentId, Money amount) {
        ProviderPayment payment = fetchPayment(providerPaymentId);
        if (payment.isCaptured()) {
            return payment;
        }
        if (payment.status() != ProviderPaymentStatus.AUTHORIZED) {
            throw new ProviderRejectedException("capture", "BAD_REQUEST_ERROR", "Payment is not authorized");
        }
        ProviderPayment captured = withStatus(payment, ProviderPaymentStatus.CAPTURED, null, null);
        payments.put(providerPaymentId, captured);
        return captured;
    }

    @Override
    public boolean verifyCheckoutSignature(String providerOrderId, String providerPaymentId, String signature) {
        return HmacSignatures.matches(sign(providerOrderId, providerPaymentId), signature);
    }

    @Override
    public boolean verifyWebhookSignature(byte[] rawBody, String signature) {
        return HmacSignatures.matches(HmacSignatures.hmacSha256Hex(SIGNING_KEY, rawBody), signature);
    }

    @Override
    public Optional<ProviderWebhookEvent> parseWebhook(String eventId, byte[] rawBody) {
        // Payments: the simulator sends no webhooks; recovery polling converges instead. Disputes have no
        // other channel, so a dispute webhook in Razorpay's format (signed with the simulator's key) is
        // accepted: scripts/smoke-test-disputes.sh uses it to raise one.
        try {
            com.fasterxml.jackson.databind.JsonNode root = JSON.readTree(rawBody);
            String event = root.path("event").asText();
            com.fasterxml.jackson.databind.JsonNode dispute = root.path("payload").path("dispute").path("entity");
            if (!RazorpayDisputes.isDisputeEvent(event) || !dispute.isObject()) {
                return Optional.empty();
            }
            Instant createdAt = root.hasNonNull("created_at") ? Instant.ofEpochSecond(root.get("created_at").asLong()) : null;
            ProviderDispute parsed = RazorpayDisputes.toDispute(dispute);
            rememberDispute(parsed);
            return Optional.of(new ProviderWebhookEvent(eventId, event, createdAt, null, parsed));
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Webhook body is not valid JSON", e);
        }
    }

    @Override
    public ProviderDispute acceptDispute(String providerDisputeId) {
        return disputes.compute(providerDisputeId, (id, d) -> {
            if (d == null) {
                throw new ProviderRejectedException("acceptDispute", "BAD_REQUEST_ERROR", "Unknown dispute");
            }
            return new ProviderDispute(id, d.providerPaymentId(), d.amount(), d.amount(), "LOST", d.reasonCode(),
                    d.phase(), d.respondBy(), d.createdAt());
        });
    }

    @Override
    public ProviderDispute contestDispute(String providerDisputeId, Money amount, String summary) {
        return disputes.compute(providerDisputeId, (id, d) -> {
            if (d == null) {
                throw new ProviderRejectedException("contestDispute", "BAD_REQUEST_ERROR", "Unknown dispute");
            }
            return new ProviderDispute(id, d.providerPaymentId(), d.amount(), d.amountDeducted(), "UNDER_REVIEW",
                    d.reasonCode(), d.phase(), d.respondBy(), d.createdAt());
        });
    }

    /** Disputes raised through a simulated webhook, so accept/contest have something to act on. */
    public void rememberDispute(ProviderDispute dispute) {
        disputes.put(dispute.providerDisputeId(), dispute);
    }

    @Override
    public CheckoutConfig checkoutConfig(CheckoutRequest request) {
        return new CheckoutConfig(null, Map.of("order_id", request.providerOrderId()), true);
    }

    /** What a patient paying on the provider's page would cause. Used by the simulated checkout page. */
    public SimulatedCheckoutResult simulatePayment(String providerOrderId, Outcome outcome) {
        SimOrder order = orders.get(providerOrderId);
        if (order == null) {
            throw new ProviderRejectedException("simulatePayment", "BAD_REQUEST_ERROR", "Unknown order");
        }
        String paymentId = "pay_sim_" + SecureTokens.alphanumeric(14);
        ProviderPaymentStatus status = outcome == Outcome.FAILURE ? ProviderPaymentStatus.FAILED
                : order.captureMode() == CaptureMode.AUTO ? ProviderPaymentStatus.CAPTURED
                : ProviderPaymentStatus.AUTHORIZED;
        payments.put(paymentId, new ProviderPayment(paymentId, providerOrderId, status, order.amount(), "upi",
                outcome == Outcome.FAILURE ? "BAD_REQUEST_ERROR" : null,
                outcome == Outcome.FAILURE ? "Simulated decline" : null));
        log.info("SIMULATED provider: payment {} on order {} -> {}", paymentId, providerOrderId, status);
        return new SimulatedCheckoutResult(providerOrderId, paymentId, sign(providerOrderId, paymentId));
    }

    private static String sign(String orderId, String paymentId) {
        return HmacSignatures.hmacSha256Hex(SIGNING_KEY, (orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8));
    }

    private static ProviderPayment withStatus(ProviderPayment p, ProviderPaymentStatus status, String code, String desc) {
        return new ProviderPayment(p.providerPaymentId(), p.providerOrderId(), status, p.amount(), p.method(), code, desc);
    }
}
