package com.hpg.gateway.filter;

import com.hpg.common.correlation.CorrelationId;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Accepts a safe client-supplied {@code X-Correlation-Id} or generates one, forwards it to the
 * downstream service and returns it to the client. Runs before security so that even 401 responses
 * carry it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdWebFilter implements WebFilter {

    public static final String ATTRIBUTE = CorrelationIdWebFilter.class.getName() + ".id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String correlationId = CorrelationId.resolve(exchange.getRequest().getHeaders().getFirst(CorrelationId.HEADER));
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(h -> h.set(CorrelationId.HEADER, correlationId))
                .build();
        ServerWebExchange mutated = exchange.mutate().request(request).build();
        mutated.getAttributes().put(ATTRIBUTE, correlationId);
        mutated.getResponse().getHeaders().set(CorrelationId.HEADER, correlationId);
        return chain.filter(mutated);
    }

    public static String of(ServerWebExchange exchange) {
        Object value = exchange.getAttribute(ATTRIBUTE);
        return value != null ? value.toString() : null;
    }
}
