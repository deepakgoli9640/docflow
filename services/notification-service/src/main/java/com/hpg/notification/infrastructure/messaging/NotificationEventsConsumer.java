package com.hpg.notification.infrastructure.messaging;

import com.hpg.events.Topics;
import com.hpg.events.payment.PaymentEventPayload;
import com.hpg.events.refund.RefundEventPayload;
import com.hpg.events.dispute.DisputeEventPayload;
import com.hpg.messaging.consumer.EventMessage;
import com.hpg.messaging.consumer.EventParser;
import com.hpg.messaging.consumer.ProcessedEvents;
import com.hpg.notification.application.NotificationIntake;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Notification intake. Only stores notifications (fast, local): delivery and its retries happen in the
 * dispatcher, so a slow hospital endpoint never holds up the Kafka partition. Idempotent via
 * processed_events plus the (source_event_id, channel) unique constraint.
 */
@Component
public class NotificationEventsConsumer {

    public static final String PAYMENTS_GROUP = "notification-payments";
    public static final String REFUNDS_GROUP = "notification-refunds";
    public static final String DISPUTES_GROUP = "notification-disputes";

    private final EventParser parser;
    private final ProcessedEvents processed;
    private final NotificationIntake intake;
    private final TransactionTemplate tx;

    public NotificationEventsConsumer(EventParser parser, ProcessedEvents processed, NotificationIntake intake,
            PlatformTransactionManager txManager) {
        this.parser = parser;
        this.processed = processed;
        this.intake = intake;
        this.tx = new TransactionTemplate(txManager);
    }

    @KafkaListener(id = PAYMENTS_GROUP, topics = Topics.PAYMENT_EVENTS, groupId = PAYMENTS_GROUP)
    public void onPaymentEvent(String value) {
        EventMessage event = parser.parse(value);
        PaymentEventPayload payload = event.payloadAs(PaymentEventPayload.class);
        tx.executeWithoutResult(s -> {
            if (processed.markProcessed(PAYMENTS_GROUP, event.eventId())) {
                intake.onPaymentEvent(event, payload);
            }
        });
    }

    @KafkaListener(id = REFUNDS_GROUP, topics = Topics.REFUND_EVENTS, groupId = REFUNDS_GROUP)
    public void onRefundEvent(String value) {
        EventMessage event = parser.parse(value);
        RefundEventPayload payload = event.payloadAs(RefundEventPayload.class);
        tx.executeWithoutResult(s -> {
            if (processed.markProcessed(REFUNDS_GROUP, event.eventId())) {
                intake.onRefundEvent(event, payload);
            }
        });
    }

    @KafkaListener(id = DISPUTES_GROUP, topics = Topics.DISPUTE_EVENTS, groupId = DISPUTES_GROUP)
    public void onDisputeEvent(String value) {
        EventMessage event = parser.parse(value);
        DisputeEventPayload payload = event.payloadAs(DisputeEventPayload.class);
        tx.executeWithoutResult(s -> {
            if (processed.markProcessed(DISPUTES_GROUP, event.eventId())) {
                intake.onDisputeEvent(event, payload);
            }
        });
    }
}
