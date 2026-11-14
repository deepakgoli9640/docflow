package com.hpg.common.error;

/** Error codes that every service can raise. */
public enum CommonErrorCode implements ErrorCode {

    VALIDATION_FAILED(400, "Request validation failed"),
    MALFORMED_REQUEST(400, "Malformed request body"),
    INVALID_SORT_FIELD(400, "Unsupported sort field"),
    MISSING_IDEMPOTENCY_KEY(400, "Idempotency-Key header is required"),
    UNAUTHENTICATED(401, "Authentication required"),
    FORBIDDEN(403, "Access denied"),
    NOT_FOUND(404, "Resource not found"),
    METHOD_NOT_ALLOWED(405, "Method not allowed"),
    CONCURRENT_MODIFICATION(409, "Resource was modified concurrently, retry the request"),
    CONFLICT(409, "Request conflicts with the current state of the resource"),
    UNSUPPORTED_MEDIA_TYPE(415, "Unsupported media type"),
    RATE_LIMITED(429, "Too many requests"),
    INTERNAL_ERROR(500, "Internal server error"),
    BAD_GATEWAY(502, "Upstream service failed"),
    SERVICE_UNAVAILABLE(503, "Service temporarily unavailable"),
    SERVICE_BUSY(503, "Service is at capacity, retry shortly"),
    UPSTREAM_TIMEOUT(504, "Upstream service did not answer in time");

    private final int httpStatus;
    private final String title;

    CommonErrorCode(int httpStatus, String title) {
        this.httpStatus = httpStatus;
        this.title = title;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }

    @Override
    public String title() {
        return title;
    }
}
