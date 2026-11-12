package com.hpg.payment.api.dto;

import com.hpg.web.dto.MoneyRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request to collect an amount for one hospital bill. Deliberately has no field for clinical data:
 * the gateway never needs to know what the bill was for.
 */
public record CreatePaymentRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._/-]+",
                message = "may contain letters, digits and . _ / -") String merchantReference,
        @NotNull @Valid MoneyRequest amount,
        @Size(max = 255) String description,
        @Valid Customer customer,
        @Size(max = 2048) String returnUrl,
        @Min(1) @Max(1440) Integer expiresInMinutes) {

    public record Customer(
            @Size(max = 100) String name,
            @Email @Size(max = 254) String email,
            @Pattern(regexp = "\\+?[0-9]{10,15}", message = "must be 10-15 digits, optionally starting with +")
            String phone) {

        @Override
        public String toString() {
            return "Customer[****]";
        }
    }
}
