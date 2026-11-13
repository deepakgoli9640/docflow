package com.hpg.payment.infrastructure.messaging;

import com.hpg.events.Topics;
import com.hpg.events.merchant.MerchantSnapshotPayload;
import com.hpg.messaging.consumer.EventMessage;
import com.hpg.messaging.consumer.EventParser;
import com.hpg.payment.application.MerchantReplicaService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Feeds the merchant replica. Idempotent by snapshot version, so no processed-events marker is needed. */
@Component
public class MerchantSnapshotConsumer {

    public static final String GROUP = "payment-merchant-replica";

    private final EventParser parser;
    private final MerchantReplicaService replicas;

    public MerchantSnapshotConsumer(EventParser parser, MerchantReplicaService replicas) {
        this.parser = parser;
        this.replicas = replicas;
    }

    @KafkaListener(id = GROUP, topics = Topics.MERCHANT_SNAPSHOTS, groupId = GROUP)
    public void onSnapshot(String value) {
        EventMessage event = parser.parse(value);
        if (MerchantSnapshotPayload.EVENT_TYPE.equals(event.eventType())) {
            replicas.apply(event.payloadAs(MerchantSnapshotPayload.class));
        }
    }
}
