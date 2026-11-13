package com.hpg.gateway.access;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Which merchants may currently use the API, as seen by the edge.
 *
 * <p>Access tokens are self-contained and live for 15 minutes, so a merchant suspended a minute ago would
 * otherwise keep working until its token expired. This closes that window: merchant-service publishes the
 * new status, the gateway records it in Redis, and the next request is refused.
 *
 * <p>Redis rather than memory because every gateway replica must see the same list, and because a replica
 * that starts later still has it without replaying anything.
 *
 * <p><b>Fail-open.</b> If Redis is unreachable, or a merchant is not in the list yet, the request is
 * allowed through. This is defence in depth, not the control itself: merchant-service refuses to issue
 * tokens to a suspended merchant, and payment-service refuses to create payments for one. Failing closed
 * here would turn a Redis outage into a full payments outage.
 */
@Component
public class MerchantAccessCache {

    static final String KEY = "hpg:merchant-access";
    private static final Logger log = LoggerFactory.getLogger(MerchantAccessCache.class);

    private final ReactiveStringRedisTemplate redis;

    public MerchantAccessCache(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    /** @return true if this merchant is known to be blocked from using the API */
    public Mono<Boolean> isBlocked(String merchantId) {
        return redis.opsForHash().get(KEY, merchantId)
                .map(status -> !allowed(String.valueOf(status)))
                .defaultIfEmpty(false)
                .timeout(Duration.ofMillis(200))
                .onErrorResume(e -> {
                    log.warn("Merchant access check unavailable ({}); allowing the request through", e.toString());
                    return Mono.just(false);
                });
    }

    public Mono<Void> record(String merchantId, String status) {
        // Snapshots are keyed by merchant id, so one merchant's updates arrive in order and the last
        // write is the current status.
        return redis.opsForHash().put(KEY, merchantId, status)
                .doOnNext(ignored -> log.info("Merchant access status merchantId={} status={}", merchantId, status))
                .then();
    }

    static boolean allowed(String status) {
        return "ACTIVE".equals(status) || "PENDING_ACTIVATION".equals(status);
    }
}
