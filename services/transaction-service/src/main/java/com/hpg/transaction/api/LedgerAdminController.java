package com.hpg.transaction.api;

import com.hpg.transaction.application.LedgerQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Tag(name = "Ledger (OPS)", description = "Account balances and integrity checks")
public class LedgerAdminController {

    private final LedgerQueryService ledger;

    public LedgerAdminController(LedgerQueryService ledger) {
        this.ledger = ledger;
    }

    @GetMapping("/api/v1/admin/ledger/accounts/{code}/balance")
    @Operation(summary = "Balance of an account per currency, e.g. PROVIDER_CLEARING or MERCHANT_PAYABLE:{merchantId}")
    public List<LedgerQueryService.Balance> balance(@PathVariable String code) {
        return ledger.balance(code);
    }

    @GetMapping("/api/v1/admin/ledger/integrity")
    @Operation(summary = "Ledger integrity check: balanced postings, debits = credits, nothing missing")
    public LedgerQueryService.IntegrityReport integrity() {
        return ledger.integrity();
    }
}
