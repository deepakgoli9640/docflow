package com.hpg.web.error;

/** One invalid field in a {@code VALIDATION_FAILED} response. */
public record FieldViolation(String field, String message) {
}
