package com.hpg.refund.infrastructure.messaging;

import com.hpg.events.Topics;
import com.hpg.events.payment.PaymentEventPayload;
import com.hpg.events.payment.PaymentEventType;
import com.hpg.messaging.consumer.EventMessage;
import com.hpg.messaging.consumer.EventParser;
import com.hpg.messaging.consumer.NonRetryableEventException;
import com.hpg.messaging.consumer.ProcessedEvents;
import com.hpg.refund.infrastructure.persistence.RefundablePaymentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * Builds {@code refundable_payments} from {@code PaymentCaptured} events (docs/03-refund-lifecycle.md §2).
 * refund-service never reads payment_db. Idempotent: the processed-event marker and the insert share
 * one transaction, and the insert itself ignores a payment it already knows.
 */
@Component
public class PaymentEventsConsumer {

    public static final String GROUP = "refund-payment-replica";
    private static final Logger log = LoggerFactory.getLogger(PaymentEventsConsumer.class);

    private final EventParser parser;
    private final ProcessedEvents processed;
    private final RefundablePaymentStore refundables;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PaymentEventsConsumer(EventParser parser, ProcessedEvents processed, RefundablePaymentStore refundables,
            PlatformTransactionManager txManager, Clock clock) {
        this.parser = parser;
        this.processed = processed;
        this.refundables = refundables;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    @KafkaListener(topics = Topics.PAYMENT_EVENTS, groupId = GROUP)
    public void onPaymentEvent(String value) {
        EventMessage event = parser.parse(value);
        if (!PaymentEventType.PaymentCaptured.name().equals(event.eventType())) {
            return;
        }
        PaymentEventPayload p = event.payloadAs(PaymentEventPayload.class);
        if (p.providerPaymentId() == null || p.provider() == null || p.amount() == null) {
            throw new NonRetryableEventException("PaymentCaptured " + event.eventId()
                    + " lacks provider payment id, provider or amount", null);
        }
        tx.executeWithoutResult(s -> {
            if (!processed.markProcessed(GROUP, event.eventId())) {
                return;
            }
            boolean inserted = refundables.insertCaptured(p.paymentId(), p.merchantId(), p.merchantReference(),
                    p.provider(), p.providerPaymentId(), p.amount(),
                    p.occurredAt() == null ? event.occurredAt() : p.occurredAt(), event.eventId(), clock.instant());
            log.info("Payment {} {} refundable ({} {})", p.paymentId(), inserted ? "is now" : "was already",
                    p.amount(), p.provider());
        });
    }
}
