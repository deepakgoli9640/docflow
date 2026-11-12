package com.hpg.merchant.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterMerchantRequest(
        @NotBlank @Size(max = 200) String legalName,
        @NotBlank @Size(max = 100) String displayName,
        @NotBlank @Email @Size(max = 254) String contactEmail,
        @Pattern(regexp = "\\+?[0-9]{10,15}", message = "must be 10-15 digits, optionally starting with +")
        String contactPhone) {
}
