package com.hpg.refund.api.dto;

import com.hpg.web.dto.MoneyRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateRefundRequest(
        @NotNull UUID paymentId,
        @NotNull @Valid MoneyRequest amount,
        @Size(max = 255) String reason) {
}
