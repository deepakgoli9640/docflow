package com.hpg.notification.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.events.Topics;
import com.hpg.events.merchant.MerchantEventType;
import com.hpg.events.merchant.MerchantSnapshotPayload;
import com.hpg.messaging.consumer.NonRetryableEventException;
import com.hpg.messaging.consumer.ReceivedEvent;
import com.hpg.notification.application.MerchantReplicaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Keeps the local copy of each merchant's webhook URL and notification preferences up to date. No dedupe
 * table is needed: snapshots carry a version that only increases, and an older one is ignored.
 */
@Component
public class MerchantSnapshotConsumer {

    private static final Logger log = LoggerFactory.getLogger(MerchantSnapshotConsumer.class);

    private final MerchantReplicaService replicas;
    private final ObjectMapper objectMapper;

    public MerchantSnapshotConsumer(MerchantReplicaService replicas, ObjectMapper objectMapper) {
        this.replicas = replicas;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = Topics.MERCHANT_SNAPSHOTS, groupId = "notification-service-merchant-replica")
    public void onMerchantSnapshot(ReceivedEvent event) {
        if (event == null || !MerchantEventType.MerchantSnapshot.name().equals(event.eventType())) {
            return;
        }
        if (event.eventVersion() > MerchantEventType.CURRENT_VERSION) {
            throw new NonRetryableEventException("Merchant snapshot version " + event.eventVersion()
                    + " is newer than this service understands (" + MerchantEventType.CURRENT_VERSION + ")");
        }
        MerchantSnapshotPayload payload = event.payload(MerchantSnapshotPayload.class, objectMapper);
        if (replicas.apply(payload)) {
            log.info("Merchant notification settings updated merchantId={} webhookConfigured={} snapshotVersion={}",
                    payload.merchantId(), payload.webhookUrl() != null, payload.version());
        }
    }
}
