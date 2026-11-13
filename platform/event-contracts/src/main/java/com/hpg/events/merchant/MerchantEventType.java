package com.hpg.events.merchant;

/** Event types on {@code merchant.snapshots.v1}. */
public enum MerchantEventType {

    /**
     * The merchant's current configuration, in full. A snapshot rather than a "what changed" event,
     * because the topic is compacted: Kafka keeps the newest record per merchant for ever, so a service
     * can rebuild its replica from the beginning of the topic instead of asking merchant-service.
     */
    MerchantSnapshot;

    public static final int CURRENT_VERSION = 1;
}
