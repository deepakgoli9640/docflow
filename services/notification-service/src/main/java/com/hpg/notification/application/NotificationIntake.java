package com.hpg.notification.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.common.money.Money;
import com.hpg.events.payment.PaymentEventPayload;
import com.hpg.events.refund.RefundEventPayload;
import com.hpg.messaging.consumer.EventMessage;
import com.hpg.notification.domain.Channel;
import com.hpg.notification.domain.Notification;
import com.hpg.notification.infrastructure.persistence.NotificationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Decides who is told what about an event and stores those notifications (delivery happens later, with
 * retries, in {@link NotificationDispatcher}). Runs in the consumer's transaction, so an event yields its
 * notifications exactly once.
 *
 * <ul>
 *   <li>Hospital webhook: every externally relevant payment and refund outcome.</li>
 *   <li>Patient email: payment captured (receipt), failed or expired — only when an address was given.</li>
 * </ul>
 */
@Service
public class NotificationIntake {

    private static final Map<String, String> PAYMENT_TYPES = Map.of(
            "PaymentAuthorized", "payment.authorized",
            "PaymentCaptured", "payment.captured",
            "PaymentFailed", "payment.failed",
            "PaymentCancelled", "payment.cancelled",
            "PaymentExpired", "payment.expired");
    private static final Map<String, String> REFUND_TYPES = Map.of(
            "RefundCompleted", "refund.completed",
            "RefundFailed", "refund.failed");
    private static final Set<String> PATIENT_EMAIL_EVENTS = Set.of("PaymentCaptured", "PaymentFailed", "PaymentExpired");

    private final NotificationRepository notifications;
    private final ObjectMapper json;
    private final MeterRegistry meters;
    private final Clock clock;

    public NotificationIntake(NotificationRepository notifications, ObjectMapper json, MeterRegistry meters,
            Clock clock) {
        this.notifications = notifications;
        this.json = json;
        this.meters = meters;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int onPaymentEvent(EventMessage event, PaymentEventPayload p) {
        String type = PAYMENT_TYPES.get(event.eventType());
        if (type == null) {
            return 0;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentId", p.paymentId());
        data.put("merchantReference", p.merchantReference());
        data.put("status", p.status());
        data.put("amount", p.amount());
        data.put("lateCapture", p.lateCapture());
        putIfPresent(data, "providerPaymentId", p.providerPaymentId());
        putIfPresent(data, "failureCode", p.failureCode());
        putIfPresent(data, "failureReason", p.failureReason());
        int created = save(webhook(event, type, p.merchantId(), p.paymentId(), null, data));

        PaymentEventPayload.Customer c = p.customer();
        if (PATIENT_EMAIL_EVENTS.contains(event.eventType()) && c != null && c.email() != null && !c.email().isBlank()) {
            created += save(new Notification(p.merchantId(), event.eventId(), event.eventType(), Channel.EMAIL, type,
                    p.paymentId(), null, event.aggregateVersion(), c.email(), emailSubject(event.eventType(), p),
                    emailBody(event.eventType(), p), clock.instant()));
        }
        return created;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int onRefundEvent(EventMessage event, RefundEventPayload r) {
        String type = REFUND_TYPES.get(event.eventType());
        if (type == null) {
            return 0;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("refundId", r.refundId());
        data.put("paymentId", r.paymentId());
        data.put("merchantReference", r.merchantReference());
        data.put("status", r.status());
        data.put("amount", r.amount());
        data.put("refundedTotal", r.refundedTotal());
        putIfPresent(data, "providerRefundId", r.providerRefundId());
        putIfPresent(data, "failureCode", r.failureCode());
        putIfPresent(data, "failureReason", r.failureReason());
        return save(webhook(event, type, r.merchantId(), r.paymentId(), r.refundId(), data));
    }

    private Notification webhook(EventMessage event, String type, UUID merchantId, UUID paymentId, UUID refundId,
            Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", event.eventId());
        body.put("type", type);
        body.put("occurredAt", event.occurredAt());
        body.put("sequence", event.aggregateVersion());
        body.put("data", data);
        try {
            return new Notification(merchantId, event.eventId(), event.eventType(), Channel.WEBHOOK, type, paymentId,
                    refundId, event.aggregateVersion(), null, null, json.writeValueAsString(body), clock.instant());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise webhook body", e);
        }
    }

    private int save(Notification n) {
        notifications.save(n);
        meters.counter("notifications.queued", "channel", n.getChannel().name(), "type", n.getPublicEventType())
                .increment();
        return 1;
    }

    private static String emailSubject(String eventType, PaymentEventPayload p) {
        return switch (eventType) {
            case "PaymentCaptured" -> "Payment received - " + p.merchantReference();
            case "PaymentFailed" -> "Payment not completed - " + p.merchantReference();
            default -> "Payment link expired - " + p.merchantReference();
        };
    }

    /** Deliberately minimal: no clinical details, no card data; amounts and the hospital's bill reference only. */
    private static String emailBody(String eventType, PaymentEventPayload p) {
        Money amount = p.amount();
        String outcome = switch (eventType) {
            case "PaymentCaptured" -> "We have received your payment of " + amount + ".";
            case "PaymentFailed" -> "Your payment of " + amount + " could not be completed. No money was taken. "
                    + "Please contact the hospital for a new payment link.";
            default -> "Your payment link for " + amount + " has expired. Please contact the hospital for a new one.";
        };
        return "Hello,\n\n" + outcome + "\n\nBill reference: " + p.merchantReference() + "\nPayment ID: " + p.paymentId()
                + "\nDate (UTC): " + (p.occurredAt() == null ? Instant.now() : p.occurredAt())
                + "\n\nThis is an automated message from the hospital's payment service.\n";
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
