package com.hpg.transaction.infrastructure.messaging;

import com.hpg.events.Topics;
import com.hpg.events.payment.PaymentEventPayload;
import com.hpg.events.refund.RefundEventPayload;
import com.hpg.events.dispute.DisputeEventPayload;
import com.hpg.events.reconciliation.SettlementConfirmedPayload;
import com.hpg.messaging.consumer.EventMessage;
import com.hpg.messaging.consumer.EventParser;
import com.hpg.messaging.consumer.ProcessedEvents;
import com.hpg.transaction.application.TransactionRecorder;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Consumes payment and refund events with blocking retry (shared error handler): order within a payment
 * matters for both the status and the ledger. Idempotent via processed_events in the same transaction.
 *
 * <p>Two listeners (two consumers), not one on both topics: a RefundCompleted waiting for its capture is
 * retried in place, and with a single consumer that wait would also stop the payment topic — which is
 * exactly where the capture it waits for comes from.
 */
@Component
public class TransactionEventsConsumer {

    public static final String PAYMENTS_GROUP = "transaction-payments";
    public static final String REFUNDS_GROUP = "transaction-refunds";
    public static final String SETTLEMENTS_GROUP = "transaction-settlements";
    public static final String DISPUTES_GROUP = "transaction-disputes";

    private final EventParser parser;
    private final ProcessedEvents processed;
    private final TransactionRecorder recorder;
    private final TransactionTemplate tx;

    public TransactionEventsConsumer(EventParser parser, ProcessedEvents processed, TransactionRecorder recorder,
            PlatformTransactionManager txManager) {
        this.parser = parser;
        this.processed = processed;
        this.recorder = recorder;
        this.tx = new TransactionTemplate(txManager);
    }

    @KafkaListener(id = PAYMENTS_GROUP, topics = Topics.PAYMENT_EVENTS, groupId = PAYMENTS_GROUP)
    public void onPaymentEvent(String value) {
        EventMessage event = parser.parse(value);
        // Bind (and validate) outside the transaction: a malformed payload is a poison pill, not retried.
        PaymentEventPayload payload = event.payloadAs(PaymentEventPayload.class);
        tx.executeWithoutResult(s -> {
            if (processed.markProcessed(PAYMENTS_GROUP, event.eventId())) {
                recorder.onPaymentEvent(event, payload);
            }
        });
    }

    @KafkaListener(id = REFUNDS_GROUP, topics = Topics.REFUND_EVENTS, groupId = REFUNDS_GROUP)
    public void onRefundEvent(String value) {
        EventMessage event = parser.parse(value);
        RefundEventPayload payload = event.payloadAs(RefundEventPayload.class);
        tx.executeWithoutResult(s -> {
            if (processed.markProcessed(REFUNDS_GROUP, event.eventId())) {
                recorder.onRefundEvent(event, payload);
            }
        });
    }

    /** Settlements confirmed by reconciliation (other event types on the topic are ignored). */
    @KafkaListener(id = SETTLEMENTS_GROUP, topics = Topics.RECONCILIATION_EVENTS, groupId = SETTLEMENTS_GROUP)
    public void onReconciliationEvent(String value) {
        EventMessage event = parser.parse(value);
        if (!SettlementConfirmedPayload.EVENT_TYPE.equals(event.eventType())) {
            return;
        }
        SettlementConfirmedPayload payload = event.payloadAs(SettlementConfirmedPayload.class);
        tx.executeWithoutResult(s -> {
            if (processed.markProcessed(SETTLEMENTS_GROUP, event.eventId())) {
                recorder.onSettlementConfirmed(event, payload);
            }
        });
    }

    /** Disputes: chargebacks and their reversals in the ledger, and the payment's timeline. */
    @KafkaListener(id = DISPUTES_GROUP, topics = Topics.DISPUTE_EVENTS, groupId = DISPUTES_GROUP)
    public void onDisputeEvent(String value) {
        EventMessage event = parser.parse(value);
        DisputeEventPayload payload = event.payloadAs(DisputeEventPayload.class);
        tx.executeWithoutResult(s -> {
            if (processed.markProcessed(DISPUTES_GROUP, event.eventId())) {
                recorder.onDisputeEvent(event, payload);
            }
        });
    }
}
