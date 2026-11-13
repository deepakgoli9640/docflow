package com.hpg.gateway.denylist;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.common.error.ErrorCode;
import com.hpg.common.security.TokenClaims;
import com.hpg.gateway.error.GatewayProblems;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Refuses requests whose token belongs to a suspended or deactivated merchant. Runs after Spring Security
 * (order 0 > -100), so the token is already validated and the principal is available.
 *
 * <p>If Redis cannot be reached the request is allowed (fail open, counted in
 * {@code gateway.deny_list.errors}): the services still refuse payments and refunds for inactive merchants
 * from their own replicas, so the deny-list is defence in depth, not the only control.
 */
public class MerchantDenyListFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(MerchantDenyListFilter.class);

    private final MerchantDenyList denyList;
    private final ObjectMapper json;
    private final MeterRegistry meters;

    public MerchantDenyListFilter(MerchantDenyList denyList, ObjectMapper json, MeterRegistry meters) {
        this.denyList = denyList;
        this.json = json;
        this.meters = meters;
    }

    public enum Code implements ErrorCode {
        MERCHANT_SUSPENDED;

        @Override
        public String code() {
            return name();
        }

        @Override
        public int httpStatus() {
            return 403;
        }

        @Override
        public String title() {
            return "Merchant is not active";
        }
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                // OPS and SERVICE tokens have no merchant id: nothing to check (mapNotNull, a null would fail the stream).
                .mapNotNull(p -> ((JwtAuthenticationToken) p).getToken().getClaimAsString(TokenClaims.MERCHANT_ID))
                .flatMap(merchantId -> denyList.isDenied(merchantId)
                        .onErrorResume(e -> {
                            log.warn("Deny-list check failed, allowing request: {}", e.toString());
                            meters.counter("gateway.deny_list.errors").increment();
                            return Mono.just(false);
                        }))
                .defaultIfEmpty(false)
                .flatMap(denied -> {
                    if (!denied) {
                        return chain.filter(exchange);
                    }
                    meters.counter("gateway.deny_list.rejections").increment();
                    return GatewayProblems.write(exchange, json, Code.MERCHANT_SUSPENDED,
                            "This merchant is suspended or deactivated. Contact HPG operations.");
                });
    }
}
