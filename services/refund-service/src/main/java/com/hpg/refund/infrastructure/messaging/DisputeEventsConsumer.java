package com.hpg.refund.infrastructure.messaging;

import com.hpg.events.Topics;
import com.hpg.events.dispute.DisputeEventPayload;
import com.hpg.events.dispute.DisputeEventType;
import com.hpg.messaging.consumer.EventMessage;
import com.hpg.messaging.consumer.EventParser;
import com.hpg.messaging.consumer.ProcessedEvents;
import com.hpg.refund.infrastructure.persistence.OpenDisputeStore;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/**
 * Keeps the set of payments under an open dispute: refunds are refused for them (PAYMENT_DISPUTED) until the
 * dispute is decided. A dispute's events share the payment's key, so they arrive in order.
 */
@Component
public class DisputeEventsConsumer {

    public static final String GROUP = "refund-disputes";

    private final EventParser parser;
    private final ProcessedEvents processed;
    private final OpenDisputeStore disputes;
    private final TransactionTemplate tx;

    public DisputeEventsConsumer(EventParser parser, ProcessedEvents processed, OpenDisputeStore disputes,
            PlatformTransactionManager txManager) {
        this.parser = parser;
        this.processed = processed;
        this.disputes = disputes;
        this.tx = new TransactionTemplate(txManager);
    }

    @KafkaListener(topics = Topics.DISPUTE_EVENTS, groupId = GROUP)
    public void onDisputeEvent(String value) {
        EventMessage event = parser.parse(value);
        boolean opened = DisputeEventType.DisputeOpened.name().equals(event.eventType());
        boolean resolved = DisputeEventType.DisputeResolved.name().equals(event.eventType());
        if (!opened && !resolved) {
            return;
        }
        DisputeEventPayload d = event.payloadAs(DisputeEventPayload.class);
        tx.executeWithoutResult(s -> {
            if (!processed.markProcessed(GROUP, event.eventId())) {
                return;
            }
            if (opened && !isFinal(d.status())) {
                disputes.opened(d.disputeId(), d.paymentId(), d.merchantId(),
                        event.occurredAt() == null ? Instant.now() : event.occurredAt());
            } else {
                disputes.resolved(d.disputeId());
            }
        });
    }

    private static boolean isFinal(String status) {
        return "WON".equals(status) || "LOST".equals(status) || "CLOSED".equals(status);
    }
}
