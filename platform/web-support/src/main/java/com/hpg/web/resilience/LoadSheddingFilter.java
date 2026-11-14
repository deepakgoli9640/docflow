package com.hpg.web.resilience;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.common.error.CommonErrorCode;
import com.hpg.web.error.ProblemDetails;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Load shedding: at most {@code maxConcurrent} requests are worked on at once; the next one waits up to
 * {@code maxWait}, then gets {@code 503 SERVICE_BUSY} with {@code Retry-After} immediately.
 *
 * <p>Why: the load test showed that without it, overload builds a queue of hundreds of requests, each holding a
 * thread while it waits 3 s for a database connection, so every caller sees multi-second latency and then
 * fails anyway. Refusing early keeps latency low for the requests that are admitted, and tells clients (who
 * retry with the same Idempotency-Key) to come back. Health and metrics endpoints are never shed, so the
 * orchestrator does not restart a pod that is merely busy.
 */
public class LoadSheddingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LoadSheddingFilter.class);

    private final Semaphore permits;
    private final long maxWaitMillis;
    private final ObjectMapper mapper;
    private final Counter shed;

    public LoadSheddingFilter(int maxConcurrent, long maxWaitMillis, ObjectMapper mapper, MeterRegistry meters) {
        this.permits = new Semaphore(maxConcurrent, true);
        this.maxWaitMillis = maxWaitMillis;
        // Same JSON as every other error response (errorCode etc. at the top level, as Spring's mix-in writes it).
        this.mapper = mapper.copy().addMixIn(ProblemDetail.class, org.springframework.http.converter.json.ProblemDetailJacksonMixin.class);
        this.shed = meters == null ? null : Counter.builder("http.server.requests.shed")
                .description("Requests refused with 503 because the service was at capacity").register(meters);
        if (meters != null) {
            meters.gauge("http.server.requests.inflight", permits, p -> maxConcurrent - p.availablePermits());
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean admitted;
        try {
            admitted = permits.tryAcquire(maxWaitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            admitted = false;
        }
        if (!admitted) {
            reject(request, response);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            permits.release();
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (shed != null) {
            shed.increment();
        }
        log.warn("Shedding {} {}: service at capacity", request.getMethod(), request.getRequestURI());
        ProblemDetail problem = ProblemDetails.of(CommonErrorCode.SERVICE_BUSY,
                "The service is at capacity. Retry in a moment with the same Idempotency-Key.");
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setStatus(503);
        response.setHeader(HttpHeaders.RETRY_AFTER, "1");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), problem);
    }
}
