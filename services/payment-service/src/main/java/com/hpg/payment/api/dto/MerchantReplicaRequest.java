package com.hpg.payment.api.dto;

import com.hpg.payment.domain.CaptureMode;
import com.hpg.payment.domain.MerchantConfigReplica;
import com.hpg.web.dto.MoneyRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;

/** TEMPORARY (Phase 3 only): body for seeding the merchant replica until merchant events exist. */
public record MerchantReplicaRequest(
        @NotBlank @Size(max = 100) String displayName,
        @NotBlank @Pattern(regexp = "PENDING_ACTIVATION|ACTIVE|SUSPENDED|DEACTIVATED") String status,
        @NotEmpty Set<@Pattern(regexp = "CARD|UPI|NETBANKING|WALLET") String> enabledMethods,
        @NotNull CaptureMode captureMode,
        @NotNull @Valid MoneyRequest minAmount,
        @NotNull @Valid MoneyRequest maxAmountPerTransaction,
        @NotNull @Valid MoneyRequest dailyLimit,
        @Min(1) int paymentTtlMinutes,
        @Min(1) int maxAttempts) {

    public MerchantConfigReplica.Snapshot toSnapshot() {
        return new MerchantConfigReplica.Snapshot(0, displayName, status, enabledMethods, captureMode,
                minAmount.toMoney(), maxAmountPerTransaction.toMoney(), dailyLimit.toMoney(), paymentTtlMinutes,
                maxAttempts);
    }
}
