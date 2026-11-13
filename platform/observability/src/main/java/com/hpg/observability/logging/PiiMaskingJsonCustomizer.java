package com.hpg.observability.logging;

import com.hpg.common.masking.LogMasking;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Masks emails, phone numbers, credentials and card-number-like values in every string of a structured
 * (JSON) log line: message, exception message and stack trace alike. Enabled with
 * {@code logging.structured.json.customizer} (set by {@link ObservabilityDefaults}).
 */
public class PiiMaskingJsonCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {

    @Override
    public void customize(JsonWriter.Members<Object> members) {
        members.applyingValueProcessor(JsonWriter.ValueProcessor.of(String.class, LogMasking::mask));
    }
}
