package io.docflow.constant;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum JobType {
    OCR("OCR"),
    TEXT_EXTRACTION("TEXT_EXTRACTION"),
    AI_SUMMARIZATION("AI_SUMMARIZATION");

    private final String value;

    JobType(String value)
    {
        this.value=value;
    }

    @JsonValue
    public String getValue()
    {
        return value;
    }

    @JsonCreator
    public static JobType fromValue(String value) {
        for (JobType type : JobType.values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown JobType value: " + value);
    }
}

