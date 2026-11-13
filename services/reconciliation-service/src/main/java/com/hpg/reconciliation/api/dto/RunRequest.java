package com.hpg.reconciliation.api.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record RunRequest(@NotNull LocalDate businessDate) {
}
