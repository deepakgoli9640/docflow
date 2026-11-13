package com.hpg.gateway.access;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.events.Topics;
import com.hpg.events.merchant.MerchantEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Builds the edge's view of merchant status from {@code merchant.snapshots.v1}.
 *
 * <p>Only two fields are read from the event, by hand, rather than binding the whole payload: the gateway
 * holds no business data and should not grow a reason to know the rest. The topic is compacted, so a
 * gateway that starts fresh reads every merchant's current status from the beginning of the topic.
 */
@Component
public class MerchantSnapshotListener {

    private static final Logger log = LoggerFactory.getLogger(MerchantSnapshotListener.class);

    private final MerchantAccessCache accessCache;
    private final ObjectMapper objectMapper;

    public MerchantSnapshotListener(MerchantAccessCache accessCache, ObjectMapper objectMapper) {
        this.accessCache = accessCache;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = Topics.MERCHANT_SNAPSHOTS, groupId = "api-gateway-merchant-access")
    public void onSnapshot(String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        try {
            JsonNode event = objectMapper.readTree(body);
            if (!MerchantEventType.MerchantSnapshot.name().equals(event.path("eventType").asText())) {
                return;
            }
            JsonNode payload = event.path("payload");
            String merchantId = payload.path("merchantId").asText(null);
            String status = payload.path("status").asText(null);
            if (merchantId == null || status == null) {
                return;
            }
            accessCache.record(merchantId, status).block();
        } catch (Exception e) {
            // Never stop the partition over one unreadable record: the gateway's copy is an optimisation,
            // and the services enforce merchant status themselves.
            log.warn("Ignoring unreadable merchant snapshot: {}", e.toString());
        }
    }
}
