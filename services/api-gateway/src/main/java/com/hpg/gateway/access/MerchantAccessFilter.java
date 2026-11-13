package com.hpg.gateway.access;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.common.error.CommonErrorCode;
import com.hpg.common.security.TokenClaims;
import com.hpg.gateway.error.GatewayProblems;
import org.springframework.core.Ordered;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Refuses requests from a merchant that has been suspended or deactivated, even if its access token is
 * still inside its lifetime. Runs after authentication, so the merchant id comes from a verified token.
 */
@Component
public class MerchantAccessFilter implements WebFilter, Ordered {

    private final MerchantAccessCache accessCache;
    private final ObjectMapper objectMapper;

    public MerchantAccessFilter(MerchantAccessCache accessCache, ObjectMapper objectMapper) {
        this.accessCache = accessCache;
        this.objectMapper = objectMapper;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(context -> context.getAuthentication())
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .mapNotNull(auth -> auth.getToken().getClaimAsString(TokenClaims.MERCHANT_ID))
                .flatMap(merchantId -> accessCache.isBlocked(merchantId)
                        .flatMap(blocked -> blocked
                                ? GatewayProblems.write(exchange, objectMapper, CommonErrorCode.FORBIDDEN,
                                        "This merchant account is suspended. Contact support.")
                                : chain.filter(exchange)))
                .switchIfEmpty(Mono.defer(() -> chain.filter(exchange)));
    }
}
