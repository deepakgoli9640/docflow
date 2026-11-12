package com.hpg.common.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Base class for expected, client-facing failures (validation of business rules, state conflicts,
 * not-found). The global exception handler turns it into an RFC 9457 Problem Details response.
 *
 * <p>Unexpected failures (bugs, infrastructure errors) must NOT be wrapped in this type: they are
 * logged with a stack trace and returned as a generic 500, so internals never leak to clients.
 */
public class BusinessException extends RuntimeException {

    private final transient ErrorCode errorCode;
    private final transient Map<String, Object> properties;

    public BusinessException(ErrorCode errorCode, String detail) {
        this(errorCode, detail, Map.of());
    }

    public BusinessException(ErrorCode errorCode, String detail, Map<String, Object> properties) {
        super(detail);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /** Extra, client-safe fields added to the Problem Details body (e.g. {@code remainingRefundable}). */
    public Map<String, Object> properties() {
        return properties;
    }
}
