package com.hpg.notification.infrastructure.messaging;

import com.hpg.events.Topics;
import com.hpg.messaging.consumer.ProcessedEvents;
import com.hpg.messaging.consumer.ReceivedEvent;
import com.hpg.notification.application.NotificationIntakeService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates notifications from payment events.
 *
 * <p>The event is claimed in {@code processed_events} and the notification rows are written in the
 * <em>same transaction</em>: either both happen or neither does. That is what makes a redelivery — which
 * Kafka's at-least-once delivery guarantees will happen eventually — harmless, and it is why a hospital
 * is not called twice for the same payment.
 */
@Component
public class PaymentEventConsumer {

    static final String GROUP = "notification-service-payment-events";
    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final NotificationIntakeService intake;
    private final ProcessedEvents processedEvents;
    private final MeterRegistry meters;

    public PaymentEventConsumer(NotificationIntakeService intake, ProcessedEvents processedEvents,
            MeterRegistry meters) {
        this.intake = intake;
        this.processedEvents = processedEvents;
        this.meters = meters;
    }

    @KafkaListener(topics = Topics.PAYMENT_EVENTS, groupId = GROUP)
    @Transactional
    public void onPaymentEvent(ReceivedEvent event) {
        if (event == null) {
            return;
        }
        if (!processedEvents.claim(GROUP, event.eventId())) {
            meters.counter("events.duplicates_skipped", "group", GROUP).increment();
            log.debug("Event {} was already processed by {}", event.eventId(), GROUP);
            return;
        }
        intake.intake(event.eventId(), event.eventType(), event.merchantId(), event.aggregateVersion(),
                event.payload());
    }
}
