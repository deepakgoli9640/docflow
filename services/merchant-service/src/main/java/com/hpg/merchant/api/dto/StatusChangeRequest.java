package com.hpg.merchant.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Every status change carries a reason: it is written to the audit log. */
public record StatusChangeRequest(@NotBlank @Size(max = 500) String reason) {
}
