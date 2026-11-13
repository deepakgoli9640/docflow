package com.hpg.reconciliation.api.dto;

import com.hpg.reconciliation.domain.ResolutionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param correctionReference required for INTERNAL_CORRECTION_POSTED: the transaction reference of the ledger
 *                            correction (posted separately), so the two actions are linked in the audit trail
 */
public record ResolveRequest(
        @NotNull ResolutionType resolutionType,
        @NotBlank @Size(max = 1000) String note,
        @Size(max = 64) String correctionReference) {
}
