package com.hpg.gateway.denylist;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/**
 * Merchants whose tokens the gateway refuses, in Redis so every gateway replica sees the same list.
 *
 * <p>merchant-service already refuses new tokens for a suspended merchant; this list closes the gap for
 * tokens issued before the suspension (up to 15 minutes). Entries therefore expire after {@code ttl}
 * (longer than any token's lifetime): after that no token of the merchant can still be valid, and the list
 * stays small. Reactivation removes the entry immediately.
 */
public class MerchantDenyList {

    static final String PREFIX = "hpg:merchant-denied:";
    private static final Set<String> DENIED_STATUSES = Set.of("SUSPENDED", "DEACTIVATED");

    private final ReactiveStringRedisTemplate redis;
    private final Duration ttl;

    public MerchantDenyList(ReactiveStringRedisTemplate redis, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    public static boolean deniedStatus(String status) {
        return DENIED_STATUSES.contains(status);
    }

    /** Applies a merchant's current status. @return true if the merchant is now denied */
    public Mono<Boolean> apply(UUID merchantId, String status) {
        String key = PREFIX + merchantId;
        if (deniedStatus(status)) {
            return redis.opsForValue().set(key, status, ttl).thenReturn(true);
        }
        return redis.delete(key).thenReturn(false);
    }

    public Mono<Boolean> isDenied(String merchantId) {
        return redis.hasKey(PREFIX + merchantId);
    }
}
