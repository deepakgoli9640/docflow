package com.hpg.gateway.denylist;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.events.Topics;
import com.hpg.events.merchant.MerchantSnapshotPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

/**
 * Keeps the deny-list in step with merchant snapshots. One consumer group for all gateway replicas: the list
 * itself is shared in Redis. Snapshots of a merchant share a partition, so they arrive in order.
 */
public class MerchantSnapshotListener {

    public static final String GROUP = "api-gateway-deny-list";
    private static final Logger log = LoggerFactory.getLogger(MerchantSnapshotListener.class);

    private final MerchantDenyList denyList;
    private final ObjectMapper json;

    public MerchantSnapshotListener(MerchantDenyList denyList, ObjectMapper json) {
        this.denyList = denyList;
        this.json = json;
    }

    @KafkaListener(id = GROUP, topics = Topics.MERCHANT_SNAPSHOTS, groupId = GROUP,
            autoStartup = "${hpg.gateway.deny-list.consumer-enabled:true}")
    public void onSnapshot(String value) {
        JsonNode event;
        try {
            event = json.readTree(value);
        } catch (IOException e) {
            log.warn("Skipping malformed merchant snapshot record");
            return;
        }
        if (!MerchantSnapshotPayload.EVENT_TYPE.equals(event.path("eventType").asText())) {
            return;
        }
        String merchantId = event.at("/payload/merchantId").asText(null);
        String status = event.at("/payload/status").asText(null);
        if (merchantId == null || status == null) {
            log.warn("Skipping merchant snapshot without merchantId/status");
            return;
        }
        // Blocking is fine here: listener threads are not the reactive request threads. An exception makes the
        // Kafka error handler retry, so a Redis outage delays the update instead of losing it.
        Boolean denied = denyList.apply(UUID.fromString(merchantId), status).block(Duration.ofSeconds(5));
        if (Boolean.TRUE.equals(denied)) {
            log.info("Merchant {} is {}: its tokens are refused at the gateway", merchantId, status);
        }
    }
}
