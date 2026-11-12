package com.hpg.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.common.error.CommonErrorCode;
import com.hpg.common.error.ErrorCode;
import com.hpg.web.error.ProblemDetails;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * 401/403 responses produced inside the security filter chain (before any controller runs) use the
 * same Problem Details format as every other error.
 */
public class ProblemDetailsSecurityHandlers {

    private final ObjectMapper objectMapper;

    public ProblemDetailsSecurityHandlers(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, ex) -> {
            response.setHeader("WWW-Authenticate", "Bearer");
            write(response, CommonErrorCode.UNAUTHENTICATED, "A valid bearer token is required.");
        };
    }

    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, ex) ->
                write(response, CommonErrorCode.FORBIDDEN, "You are not allowed to perform this operation.");
    }

    private void write(HttpServletResponse response, ErrorCode code, String detail) throws IOException {
        ProblemDetail problem = ProblemDetails.of(code, detail);
        response.setStatus(code.httpStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
