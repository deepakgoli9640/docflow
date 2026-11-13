package com.hpg.gateway.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.common.error.ErrorCode;
import com.hpg.gateway.filter.CorrelationIdWebFilter;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Writes RFC 9457 Problem Details from the reactive gateway, in the same shape the services use. */
public final class GatewayProblems {

    private GatewayProblems() {
    }

    public static Mono<Void> write(ServerWebExchange exchange, ObjectMapper mapper, ErrorCode code, String detail) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://docs.hpg.example/errors/" + code.code().toLowerCase(Locale.ROOT).replace('_', '-'));
        body.put("title", code.title());
        body.put("status", code.httpStatus());
        body.put("detail", detail);
        body.put("instance", exchange.getRequest().getPath().value());
        body.put("errorCode", code.code());
        body.put("correlationId", CorrelationIdWebFilter.of(exchange));
        body.put("timestamp", Instant.now().toString());
        response.setRawStatusCode(code.httpStatus());
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        try {
            DataBuffer buffer = response.bufferFactory().wrap(mapper.writeValueAsBytes(body));
            return response.writeWith(Mono.just(buffer));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return Mono.error(e);
        }
    }
}
