package com.hpg.payment.api.dto;

import jakarta.validation.constraints.Size;

public record CancelPaymentRequest(@Size(max = 500) String reason) {
}
