package com.hpg.refund.infrastructure.messaging;

import com.hpg.events.Topics;
import com.hpg.events.merchant.MerchantSnapshotPayload;
import com.hpg.messaging.consumer.EventMessage;
import com.hpg.messaging.consumer.EventParser;
import com.hpg.refund.infrastructure.persistence.MerchantReplicaStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Clock;

/** Merchant status and refund window from merchant.snapshots.v1. Idempotent by snapshot version. */
@Component
public class MerchantSnapshotConsumer {

    public static final String GROUP = "refund-merchant-replica";
    private static final Logger log = LoggerFactory.getLogger(MerchantSnapshotConsumer.class);

    private final EventParser parser;
    private final MerchantReplicaStore replicas;
    private final Clock clock;

    public MerchantSnapshotConsumer(EventParser parser, MerchantReplicaStore replicas, Clock clock) {
        this.parser = parser;
        this.replicas = replicas;
        this.clock = clock;
    }

    @KafkaListener(id = GROUP, topics = Topics.MERCHANT_SNAPSHOTS, groupId = GROUP)
    public void onSnapshot(String value) {
        EventMessage event = parser.parse(value);
        if (!MerchantSnapshotPayload.EVENT_TYPE.equals(event.eventType())) {
            return;
        }
        MerchantSnapshotPayload s = event.payloadAs(MerchantSnapshotPayload.class);
        if (replicas.upsert(s.merchantId(), s.status(), s.refundWindowDays(), s.snapshotVersion(), clock.instant())) {
            log.info("Merchant replica updated merchantId={} version={} status={}", s.merchantId(), s.snapshotVersion(),
                    s.status());
        }
    }
}
