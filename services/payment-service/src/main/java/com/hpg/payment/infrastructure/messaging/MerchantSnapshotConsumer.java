package com.hpg.payment.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.events.Topics;
import com.hpg.events.merchant.MerchantEventType;
import com.hpg.events.merchant.MerchantSnapshotPayload;
import com.hpg.messaging.consumer.NonRetryableEventException;
import com.hpg.messaging.consumer.ReceivedEvent;
import com.hpg.payment.application.MerchantReplicaService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Keeps payment-service's local copy of merchant configuration up to date
 * (docs/01-architecture-overview.md §4.2).
 *
 * <p>Creating a payment therefore never calls merchant-service: limits, capture mode and the hospital's
 * display name are read from a local table, so payments keep working while merchant-service is down or
 * being deployed. The price is eventual consistency — a configuration change takes effect here a moment
 * later, which is acceptable for limits and is not relied on for suspension (the gateway blocks a
 * suspended merchant's requests, and token issuance stops immediately at the source).
 *
 * <p>No dedupe table is needed: snapshots carry a version that only ever increases, and the replica
 * ignores anything not newer than what it holds. Redelivery, a replay of the compacted topic from the
 * beginning, and events arriving out of order all converge on the same state.
 */
@Component
public class MerchantSnapshotConsumer {

    private static final Logger log = LoggerFactory.getLogger(MerchantSnapshotConsumer.class);

    private final MerchantReplicaService replicas;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meters;

    public MerchantSnapshotConsumer(MerchantReplicaService replicas, ObjectMapper objectMapper, MeterRegistry meters) {
        this.replicas = replicas;
        this.objectMapper = objectMapper;
        this.meters = meters;
    }

    @KafkaListener(topics = Topics.MERCHANT_SNAPSHOTS, groupId = "payment-service-merchant-replica")
    public void onMerchantSnapshot(ReceivedEvent event) {
        if (event == null) {
            return; // tombstone on the compacted topic: merchant-service does not emit these today
        }
        if (!MerchantEventType.MerchantSnapshot.name().equals(event.eventType())) {
            return;
        }
        if (event.eventVersion() > MerchantEventType.CURRENT_VERSION) {
            // A newer producer than this service understands. Guessing at the payload could write wrong
            // limits, so it goes to the dead-letter topic and someone deploys the newer consumer.
            throw new NonRetryableEventException("Merchant snapshot version " + event.eventVersion()
                    + " is newer than this service understands (" + MerchantEventType.CURRENT_VERSION + ")");
        }
        MerchantSnapshotPayload payload = event.payload(MerchantSnapshotPayload.class, objectMapper);
        boolean applied = replicas.apply(payload);
        meters.counter("merchant.snapshots.consumed", "applied", String.valueOf(applied)).increment();
        if (applied) {
            log.info("Merchant replica updated merchantId={} status={} snapshotVersion={}",
                    payload.merchantId(), payload.status(), payload.version());
        } else {
            log.debug("Ignored merchant snapshot v{} for merchantId={}: not newer than the stored one",
                    payload.version(), payload.merchantId());
        }
    }
}
