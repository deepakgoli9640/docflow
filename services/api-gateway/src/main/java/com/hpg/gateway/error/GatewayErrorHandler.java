package com.hpg.gateway.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.common.error.CommonErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

/**
 * Converts gateway-level failures (no route, downstream unreachable, timeouts) into Problem Details.
 * Downstream services' own error responses pass through untouched; this only handles errors that
 * happen in the gateway itself.
 */
@Component
@Order(-2) // before Spring Boot's DefaultErrorWebExceptionHandler (-1)
public class GatewayErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);
    private final ObjectMapper mapper;

    public GatewayErrorHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        CommonErrorCode code;
        String detail;
        if (hasCause(ex, ConnectException.class)
                || ex instanceof org.springframework.cloud.gateway.support.NotFoundException) {
            log.warn("Downstream unavailable for {}: {}", exchange.getRequest().getPath(), ex.toString());
            code = CommonErrorCode.SERVICE_UNAVAILABLE;
            detail = "The service is temporarily unavailable. Retry later.";
        } else if (hasCause(ex, java.io.IOException.class)) {
            // Connection reset / closed mid-response: the backend was reached but failed.
            log.warn("Downstream connection failed for {}: {}", exchange.getRequest().getPath(), ex.toString());
            code = CommonErrorCode.BAD_GATEWAY;
            detail = "The upstream service failed while handling the request. Retry later with the same Idempotency-Key.";
        } else if (hasCause(ex, TimeoutException.class)) {
            log.warn("Downstream timeout for {}", exchange.getRequest().getPath());
            code = CommonErrorCode.SERVICE_UNAVAILABLE;
            detail = "The service did not respond in time. Retry later with the same Idempotency-Key.";
        } else if (ex instanceof ResponseStatusException rse) {
            code = switch (rse.getStatusCode().value()) {
                case 404 -> CommonErrorCode.NOT_FOUND;
                case 405 -> CommonErrorCode.METHOD_NOT_ALLOWED;
                case 413, 400 -> CommonErrorCode.MALFORMED_REQUEST;
                case 415 -> CommonErrorCode.UNSUPPORTED_MEDIA_TYPE;
                case 429 -> CommonErrorCode.RATE_LIMITED;
                case 503 -> CommonErrorCode.SERVICE_UNAVAILABLE;
                default -> rse.getStatusCode().is4xxClientError() ? CommonErrorCode.CONFLICT : CommonErrorCode.INTERNAL_ERROR;
            };
            detail = code == CommonErrorCode.NOT_FOUND ? "No API route matches this path." : code.title();
        } else {
            log.error("Unexpected gateway error for {}", exchange.getRequest().getPath(), ex);
            code = CommonErrorCode.INTERNAL_ERROR;
            detail = "An unexpected error occurred. Quote the correlationId when contacting support.";
        }
        return GatewayProblems.write(exchange, mapper, code, detail);
    }

    private static boolean hasCause(Throwable ex, Class<? extends Throwable> type) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
