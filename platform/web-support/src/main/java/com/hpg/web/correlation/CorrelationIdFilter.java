package com.hpg.web.correlation;

import com.hpg.common.correlation.CorrelationId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Puts the request's correlation ID into the logging MDC and echoes it in the response.
 *
 * <p>The gateway normally sets the header already; the service still validates it because services
 * must not trust that every caller came through the gateway.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = CorrelationId.resolve(request.getHeader(CorrelationId.HEADER));
        MDC.put(CorrelationId.MDC_KEY, correlationId);
        request.setAttribute(CorrelationId.MDC_KEY, correlationId);
        response.setHeader(CorrelationId.HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }

    /** The correlation ID of the current request, for code that has the request but not the MDC. */
    public static String current(HttpServletRequest request) {
        Object value = request.getAttribute(CorrelationId.MDC_KEY);
        return value != null ? value.toString() : MDC.get(CorrelationId.MDC_KEY);
    }
}
