package com.hpg.payment.api;

import com.hpg.common.money.Money;
import com.hpg.payment.application.DisputeService;
import com.hpg.payment.application.IdempotencyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import com.hpg.web.security.MerchantPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Disputes (chargebacks) on the hospital's payments: see them and respond before the provider's deadline. */
@RestController
@RequestMapping("/api/v1/disputes")
@Tag(name = "Disputes", description = "Chargebacks raised by patients' banks")
public class DisputeController {

    private final DisputeService disputes;

    public DisputeController(DisputeService disputes) {
        this.disputes = disputes;
    }

    public record DisputeResponse(UUID id, UUID paymentId, String providerDisputeId, Money amount, Money amountDeducted,
            String status, String reasonCode, String phase, Instant respondBy, String merchantResponse,
            String responseSummary, Instant createdAt, Instant updatedAt, Instant resolvedAt) {

        static DisputeResponse of(DisputeService.Dispute d) {
            return new DisputeResponse(d.id(), d.paymentId(), d.providerDisputeId(), d.amount(), d.amountDeducted(),
                    d.status(), d.reasonCode(), d.phase(), d.respondBy(), d.merchantResponse(), d.responseSummary(),
                    d.createdAt(), d.updatedAt(), d.resolvedAt());
        }
    }

    public record ContestRequest(@NotBlank @Size(max = 4000) String summary) {
    }

    @GetMapping
    @Operation(summary = "The hospital's disputes, newest first", description = "Filter: status (OPEN, UNDER_REVIEW, WON, LOST, CLOSED).")
    public List<DisputeResponse> list(MerchantPrincipal principal, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {
        return disputes.list(principal.merchantId(), status, Math.max(1, Math.min(limit, 200))).stream()
                .map(DisputeResponse::of).toList();
    }

    @GetMapping("/{disputeId}")
    @Operation(summary = "One dispute")
    public DisputeResponse get(MerchantPrincipal principal, @PathVariable UUID disputeId) {
        return DisputeResponse.of(disputes.get(principal.merchantId(), disputeId));
    }

    @PostMapping("/{disputeId}/accept")
    @Operation(summary = "Accept the dispute", description = "The patient keeps the money. Requires Idempotency-Key: "
            + "a retry with the same key returns the dispute (Idempotent-Replayed: true) without asking the provider "
            + "again. 409 DISPUTE_NOT_OPEN if it was contested or decided.")
    public ResponseEntity<DisputeResponse> accept(MerchantPrincipal principal, @PathVariable UUID disputeId,
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String key) {
        return respond(disputes.accept(principal.merchantId(), disputeId, key));
    }

    @PostMapping("/{disputeId}/contest")
    @Operation(summary = "Contest the dispute", description = "With a summary of the evidence (treatment given, consent, "
            + "invoice). Requires Idempotency-Key, like accept; the same key with a different summary is 422.")
    public ResponseEntity<DisputeResponse> contest(MerchantPrincipal principal, @PathVariable UUID disputeId,
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String key,
            @Valid @RequestBody ContestRequest request) {
        return respond(disputes.contest(principal.merchantId(), disputeId, request.summary(), key));
    }

    private static ResponseEntity<DisputeResponse> respond(DisputeService.Response r) {
        ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
        if (r.replayed()) {
            ok.header("Idempotent-Replayed", "true");
        }
        return ok.body(DisputeResponse.of(r.dispute()));
    }
}
