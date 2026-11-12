package com.hpg.common.correlation;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The business-level request identifier that follows a request through HTTP calls, logs, events and
 * webhooks. Clients may supply one; otherwise the gateway generates it.
 *
 * <p>Client-supplied values are validated because they are written into logs: an unvalidated header
 * is a log-injection vector (e.g. a value containing newlines that forges extra log lines).
 */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private CorrelationId() {
    }

    public static boolean isValid(String value) {
        return value != null && VALID.matcher(value).matches();
    }

    /** Returns the supplied value if it is safe to use, otherwise a newly generated one. */
    public static String resolve(String supplied) {
        return isValid(supplied) ? supplied : generate();
    }

    public static String generate() {
        return UUID.randomUUID().toString();
    }
}
