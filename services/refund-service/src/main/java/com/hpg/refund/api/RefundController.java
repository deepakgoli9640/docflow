package com.hpg.refund.api;

import com.hpg.refund.api.dto.CreateRefundRequest;
import com.hpg.refund.api.dto.RefundResponse;
import com.hpg.refund.application.CreateRefundCommand;
import com.hpg.refund.application.IdempotencyService;
import com.hpg.refund.application.RefundQueryService;
import com.hpg.refund.application.RefundResult;
import com.hpg.refund.application.RefundService;
import com.hpg.refund.domain.RefundStatus;
import com.hpg.web.paging.PageResponse;
import com.hpg.web.paging.SortValidator;
import com.hpg.web.security.MerchantPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/refunds")
@Tag(name = "Refunds", description = "Full and partial refunds of captured payments")
public class RefundController {

    private static final Set<String> SORTABLE = Set.of("createdAt", "updatedAt", "amount", "status");

    private final RefundService refunds;
    private final RefundQueryService queries;

    public RefundController(RefundService refunds, RefundQueryService queries) {
        this.refunds = refunds;
        this.queries = queries;
    }

    @PostMapping
    @Operation(summary = "Refund all or part of a captured payment",
            description = "Requires Idempotency-Key. 201 with the refund; its status is COMPLETED, PROCESSING "
                    + "(provider settles asynchronously), UNKNOWN (verified automatically) or FAILED. "
                    + "409 PAYMENT_NOT_YET_REFUNDABLE if the capture has not reached refund-service yet (retry). "
                    + "422 REFUND_EXCEEDS_CAPTURED_AMOUNT with remainingRefundable.")
    public ResponseEntity<RefundResponse> create(MerchantPrincipal principal,
            @Parameter(required = true) @RequestHeader(value = IdempotencyService.HEADER, required = false) String key,
            @Valid @RequestBody CreateRefundRequest request) {
        RefundResult result = refunds.create(new CreateRefundCommand(principal.merchantId(), request.paymentId(),
                request.amount().toMoney(), request.reason()), key, request);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.httpStatus())
                .location(URI.create("/api/v1/refunds/" + result.refund().getId()));
        if (result.replayed()) {
            builder.header("Idempotent-Replayed", "true");
        }
        return builder.body(RefundResponse.summary(result.refund()));
    }

    @GetMapping("/{refundId}")
    @Operation(summary = "Get a refund with its status history")
    public RefundResponse get(MerchantPrincipal principal, @PathVariable UUID refundId) {
        var details = queries.get(principal.merchantId(), refundId);
        return RefundResponse.details(details.refund(), details.history());
    }

    @GetMapping
    @Operation(summary = "List refunds", description = "Filters: paymentId, status. Sortable by createdAt, updatedAt, "
            + "amount, status.")
    public PageResponse<RefundResponse> list(MerchantPrincipal principal,
            @RequestParam(required = false) UUID paymentId,
            @RequestParam(required = false) RefundStatus status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        SortValidator.requireAllowed(pageable, SORTABLE);
        return PageResponse.from(queries.list(principal.merchantId(), paymentId, status, pageable),
                RefundResponse::summary);
    }
}
