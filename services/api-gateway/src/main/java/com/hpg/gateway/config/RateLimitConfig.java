package com.hpg.gateway.config;

import com.hpg.common.security.TokenClaims;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * Keys for Redis token-bucket rate limiting. Redis is used because limits must be shared by all
 * gateway replicas; an in-memory counter per replica would multiply the effective limit by the replica
 * count.
 *
 * <p>Failure mode: if Redis is unreachable, Spring Cloud Gateway's RedisRateLimiter allows the request
 * (fails open) and logs the error. That is a deliberate availability choice for rate limiting — it is
 * a protection, not a correctness control.
 */
@Configuration
public class RateLimitConfig {

    /** Authenticated traffic: one bucket per merchant (all of its API keys share it), else per client. */
    @Bean
    @Primary
    public KeyResolver principalKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(auth -> {
                    String merchantId = auth.getToken().getClaimAsString(TokenClaims.MERCHANT_ID);
                    return merchantId != null ? "merchant:" + merchantId : "client:" + auth.getName();
                })
                .switchIfEmpty(Mono.defer(() -> Mono.just("ip:" + clientIp(exchange))));
    }

    /** Unauthenticated endpoints (token, registration): one bucket per client IP. */
    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.just("ip:" + clientIp(exchange));
    }

    /**
     * Uses the TCP peer address. Behind a load balancer this is the balancer's address; production must
     * configure trusted proxies and use X-Forwarded-For (never trust that header from the internet).
     */
    private static String clientIp(org.springframework.web.server.ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote != null && remote.getAddress() != null ? remote.getAddress().getHostAddress() : "unknown";
    }
}
