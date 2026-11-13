package com.hpg.transaction.api;

import com.hpg.transaction.api.dto.TransactionResponse;
import com.hpg.transaction.application.TransactionQueryService;
import com.hpg.transaction.domain.TransactionStatus;
import com.hpg.transaction.domain.TransactionType;
import com.hpg.web.paging.PageResponse;
import com.hpg.web.paging.SortValidator;
import com.hpg.web.security.MerchantPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@Tag(name = "Transactions", description = "Transaction history, status and summary reports (merchant's own data)")
public class TransactionController {

    private static final Set<String> SORTABLE = Set.of("initiatedAt", "completedAt", "lastEventAt", "amount", "status");

    private final TransactionQueryService queries;

    public TransactionController(TransactionQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/api/v1/transactions")
    @Operation(summary = "List transactions (payments and refunds)",
            description = "Filters: type, status, paymentId, from/to (initiatedAt). Sortable by initiatedAt, completedAt, "
                    + "lastEventAt, amount, status. Built from events, so it can trail the payment API by moments.")
    public PageResponse<TransactionResponse> list(MerchantPrincipal principal,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(required = false) UUID paymentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 20, sort = "initiatedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        SortValidator.requireAllowed(pageable, SORTABLE);
        return PageResponse.from(queries.list(principal.merchantId(),
                new TransactionQueryService.Filter(type, status, paymentId, from, to), pageable),
                TransactionResponse::summary);
    }

    @GetMapping("/api/v1/transactions/{transactionRef}")
    @Operation(summary = "One transaction with its status timeline and ledger entries")
    public TransactionResponse get(MerchantPrincipal principal, @PathVariable String transactionRef) {
        return TransactionResponse.details(queries.get(principal.merchantId(), transactionRef));
    }

    @GetMapping("/api/v1/reports/summary")
    @Operation(summary = "Captured, refunded and net totals per currency for a period (by completion time)")
    public List<TransactionQueryService.Summary> summary(MerchantPrincipal principal,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return queries.summary(principal.merchantId(), from, to);
    }
}
