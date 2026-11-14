package com.hpg.web.resilience;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LoadSheddingFilterTest {

    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    void refusesFastWithRetryAfterWhenAtCapacityAndRecoversAfterwards() throws Exception {
        LoadSheddingFilter filter = new LoadSheddingFilter(1, 50, json, meters);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread busy = new Thread(() -> {
            try {
                filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/payments"), new MockHttpServletResponse(),
                        (req, res) -> {
                            inside.countDown();
                            try {
                                release.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        busy.start();
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        MockHttpServletResponse refused = new MockHttpServletResponse();
        long started = System.nanoTime();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/payments/x"), refused,
                (req, res) -> { throw new AssertionError("must not reach the application"); });
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1000);
        assertThat(refused.getStatus()).isEqualTo(503);
        assertThat(refused.getHeader("Retry-After")).isEqualTo("1");
        JsonNode body = json.readTree(refused.getContentAsString());
        assertThat(body.get("errorCode").asText()).isEqualTo("SERVICE_BUSY");
        assertThat(meters.counter("http.server.requests.shed").count()).isEqualTo(1);

        // Health checks are never shed, even at capacity.
        MockHttpServletResponse health = new MockHttpServletResponse();
        MockHttpServletRequest probe = new MockHttpServletRequest("GET", "/actuator/health/readiness");
        filter.doFilter(probe, health, (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(200));
        assertThat(health.getStatus()).isEqualTo(200);

        release.countDown();
        busy.join(5000);
        MockHttpServletResponse after = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/payments/x"), after,
                (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(200));
        assertThat(after.getStatus()).isEqualTo(200);
    }
}
