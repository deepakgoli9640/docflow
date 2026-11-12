package com.hpg.web.error;

import com.hpg.common.correlation.CorrelationId;
import com.hpg.common.error.ErrorCode;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * Builds RFC 9457 Problem Details bodies with the HPG extension fields:
 * {@code errorCode}, {@code correlationId}, {@code timestamp}.
 */
public final class ProblemDetails {

    public static final String TYPE_BASE = "https://docs.hpg.example/errors/";

    private ProblemDetails() {
    }

    public static ProblemDetail of(ErrorCode errorCode, String detail) {
        return of(errorCode, detail, Map.of());
    }

    public static ProblemDetail of(ErrorCode errorCode, String detail, Map<String, Object> properties) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(errorCode.httpStatus()), detail);
        problem.setTitle(errorCode.title());
        problem.setType(URI.create(TYPE_BASE + errorCode.code().toLowerCase(Locale.ROOT).replace('_', '-')));
        decorate(problem, errorCode);
        properties.forEach(problem::setProperty);
        return problem;
    }

    /** Adds the standard extension fields to a ProblemDetail created elsewhere (e.g. by Spring MVC). */
    public static void decorate(ProblemDetail problem, ErrorCode errorCode) {
        problem.setProperty("errorCode", errorCode.code());
        problem.setProperty("correlationId", MDC.get(CorrelationId.MDC_KEY));
        problem.setProperty("timestamp", Instant.now().toString());
    }
}
