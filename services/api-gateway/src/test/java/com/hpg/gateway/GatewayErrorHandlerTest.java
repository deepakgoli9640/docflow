package com.hpg.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.gateway.error.GatewayErrorHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayErrorHandlerTest {

    private final GatewayErrorHandler handler = new GatewayErrorHandler(new ObjectMapper());

    private int statusFor(Throwable error) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/x"));
        handler.handle(exchange, error).block();
        return exchange.getResponse().getStatusCode().value();
    }

    @Test
    void mapsFailureKinds() {
        assertThat(statusFor(new RuntimeException(new ConnectException("refused")))).isEqualTo(503);
        assertThat(statusFor(new RuntimeException(new TimeoutException()))).isEqualTo(503);
        assertThat(statusFor(new IOException("Connection reset by peer"))).isEqualTo(502);
        assertThat(statusFor(new ResponseStatusException(HttpStatus.NOT_FOUND))).isEqualTo(404);
        assertThat(statusFor(new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE))).isEqualTo(400);
        assertThat(statusFor(new IllegalStateException("bug"))).isEqualTo(500);
    }

    @Test
    void bodyDoesNotLeakExceptionMessages() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/x"));
        handler.handle(exchange, new IllegalStateException("password=hunter2")).block();
        assertThat(exchange.getResponse().getBodyAsString().block()).doesNotContain("hunter2").contains("INTERNAL_ERROR");
    }
}
