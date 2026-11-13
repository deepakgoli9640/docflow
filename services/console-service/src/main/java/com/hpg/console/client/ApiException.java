package com.hpg.console.client;

import java.util.List;
import java.util.Map;

/**
 * An error returned by one of the APIs, kept in the shape the API sent it (RFC 9457 Problem Details)
 * so the console can show the service's own message rather than inventing one.
 *
 * @param status     HTTP status
 * @param errorCode  e.g. {@code DAILY_LIMIT_EXCEEDED}
 * @param detail     the service's human-readable sentence
 * @param violations field-level problems, when the service sent any
 * @param properties extra fields such as {@code remainingToday}, used to make a message concrete
 */
public class ApiException extends RuntimeException {

    private final transient Problem problem;

    public ApiException(Problem problem) {
        super(problem.status() + " " + problem.errorCode() + ": " + problem.detail());
        this.problem = problem;
    }

    public Problem problem() {
        return problem;
    }

    public record Problem(int status, String errorCode, String detail, List<Violation> violations,
            Map<String, Object> properties) {

        public record Violation(String field, String message) {
        }

        /** What to show someone when the API did not explain itself. */
        public static Problem unexpected(int status) {
            return new Problem(status, "UNEXPECTED", "The request could not be completed (HTTP " + status + ").",
                    List.of(), Map.of());
        }
    }
}
