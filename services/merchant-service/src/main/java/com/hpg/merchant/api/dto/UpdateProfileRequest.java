package com.hpg.merchant.api.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** PATCH semantics: null fields are left unchanged. */
public record UpdateProfileRequest(
        @Size(min = 1, max = 100) String displayName,
        @Pattern(regexp = "\\+?[0-9]{10,15}", message = "must be 10-15 digits, optionally starting with +")
        String contactPhone) {
}
