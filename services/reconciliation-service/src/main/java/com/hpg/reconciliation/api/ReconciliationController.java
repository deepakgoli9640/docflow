package com.hpg.reconciliation.api;

import com.hpg.common.error.BusinessException;
import com.hpg.reconciliation.api.dto.BatchResponse;
import com.hpg.reconciliation.api.dto.RecordResponse;
import com.hpg.reconciliation.api.dto.ResolveRequest;
import com.hpg.reconciliation.api.dto.RunRequest;
import com.hpg.reconciliation.application.DiscrepancyService;
import com.hpg.reconciliation.application.ReconQueryService;
import com.hpg.reconciliation.application.ReconciliationRunner;
import com.hpg.reconciliation.domain.BatchStatus;
import com.hpg.reconciliation.domain.MatchStatus;
import com.hpg.reconciliation.domain.ReconErrorCode;
import com.hpg.reconciliation.domain.ReconciliationBatch;
import com.hpg.reconciliation.domain.ResolutionStatus;
import com.hpg.web.paging.PageResponse;
import com.hpg.web.paging.SortValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reconciliation")
@Tag(name = "Reconciliation (OPS)", description = "Ledger vs provider settlement: runs, batches, discrepancies, reports")
public class ReconciliationController {

    private static final Set<String> BATCH_SORT = Set.of("startedAt", "businessDate", "completedAt");
    private static final Set<String> RECORD_SORT = Set.of("status", "providerReference", "createdAt");

    private final ReconciliationRunner runner;
    private final ReconQueryService queries;
    private final DiscrepancyService discrepancies;

    public ReconciliationController(ReconciliationRunner runner, ReconQueryService queries, DiscrepancyService discrepancies) {
        this.runner = runner;
        this.queries = queries;
        this.discrepancies = discrepancies;
    }

    @PostMapping("/runs")
    @Operation(summary = "Reconcile a business date against the provider's settlement API",
            description = "202: the batch runs in the background; poll the Location. 422 if this deployment has no provider API.")
    public ResponseEntity<BatchResponse> run(@Valid @RequestBody RunRequest request) {
        return accepted(runner.startFromApi(request.businessDate()));
    }

    @PostMapping(path = "/settlement-files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a settlement CSV (≤ 20 MB) for a business date",
            description = "Columns: entity_id,type,order_id,amount,fee,tax,currency,settlement_id,settled_at,created_at. "
                    + "409 DUPLICATE_SETTLEMENT_FILE if the same file was loaded before. Bad rows are reported, not fatal.")
    public ResponseEntity<BatchResponse> upload(@RequestPart("file") MultipartFile file,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        if (file.isEmpty()) {
            throw new BusinessException(ReconErrorCode.INVALID_SETTLEMENT_FILE, "The uploaded file is empty.");
        }
        String name = file.getOriginalFilename() == null ? "settlement.csv"
                : file.getOriginalFilename().replaceAll("[^A-Za-z0-9._ -]", "_");
        return accepted(runner.startFromFile(businessDate, name.length() > 255 ? name.substring(0, 255) : name, () -> {
            try {
                return file.getInputStream();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }));
    }

    @GetMapping("/batches")
    @Operation(summary = "List batches", description = "Filters: businessDate, status. Newest first.")
    public PageResponse<BatchResponse> batches(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate,
            @RequestParam(required = false) BatchStatus status,
            @PageableDefault(size = 20, sort = "startedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        SortValidator.requireAllowed(pageable, BATCH_SORT);
        return PageResponse.from(queries.batches(businessDate, status, pageable), BatchResponse::from);
    }

    @GetMapping("/batches/{id}")
    @Operation(summary = "A batch with its counts")
    public BatchResponse batch(@PathVariable UUID id) {
        return BatchResponse.from(queries.batch(id));
    }

    @GetMapping("/batches/{id}/records")
    @Operation(summary = "A batch's records", description = "Filters: status (match result), resolutionStatus.")
    public PageResponse<RecordResponse> records(@PathVariable UUID id, @RequestParam(required = false) MatchStatus status,
            @RequestParam(required = false) ResolutionStatus resolutionStatus,
            @PageableDefault(size = 50, sort = "status") Pageable pageable) {
        SortValidator.requireAllowed(pageable, RECORD_SORT);
        return PageResponse.from(queries.records(id, status, resolutionStatus, pageable), RecordResponse::from);
    }

    @GetMapping("/records/{id}")
    @Operation(summary = "One reconciliation record")
    public RecordResponse record(@PathVariable UUID id) {
        return RecordResponse.from(queries.record(id));
    }

    @PostMapping("/records/{id}/review")
    @Operation(summary = "Take a discrepancy into review (OPEN → IN_REVIEW)")
    public RecordResponse review(@PathVariable UUID id) {
        return RecordResponse.from(discrepancies.startReview(id));
    }

    @PostMapping("/records/{id}/resolve")
    @Operation(summary = "Resolve a discrepancy", description = "Changes the reconciliation record only, never money records. "
            + "INTERNAL_CORRECTION_POSTED needs the correction's transaction reference.")
    public RecordResponse resolve(@PathVariable UUID id, @Valid @RequestBody ResolveRequest request) {
        return RecordResponse.from(discrepancies.resolve(id, request.resolutionType(), request.note(),
                request.correctionReference()));
    }

    @GetMapping(path = "/reports/daily", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Daily report: the newest completed batch for a date with all its records")
    public Map<String, Object> daily(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        var report = queries.daily(date);
        return Map.of("batch", BatchResponse.from(report.batch()),
                "records", report.records().stream().map(RecordResponse::from).toList());
    }

    @GetMapping(path = "/reports/daily", produces = "text/csv")
    @Operation(summary = "Daily report as CSV (Accept: text/csv)")
    public ResponseEntity<String> dailyCsv(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        var report = queries.daily(date);
        StringBuilder csv = new StringBuilder("status,entity_type,provider_reference,internal_transaction_ref,merchant_id,"
                + "internal_amount,provider_amount,currency,resolution_status,resolution_type,detail\n");
        for (var r : report.records()) {
            csv.append(String.join(",", cell(r.getStatus().name()), cell(r.getEntityType()), cell(r.getProviderReference()),
                    cell(r.getInternalTransactionRef()), cell(r.getMerchantId()), cell(r.getInternalAmount()),
                    cell(r.getProviderAmount()), cell(r.getCurrency()), cell(r.getResolutionStatus()),
                    cell(r.getResolutionType()), cell(r.getDetail()))).append('\n');
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"reconciliation-" + date + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.toString());
    }

    /** CSV cell: quoted when needed; a leading = + - @ is neutralised so spreadsheets never run it as a formula. */
    static String cell(Object value) {
        if (value == null) {
            return "";
        }
        String s = value instanceof java.math.BigDecimal d ? d.toPlainString() : value.toString();
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0 && !(value instanceof java.math.BigDecimal)) {
            s = "'" + s;
        }
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    private static ResponseEntity<BatchResponse> accepted(ReconciliationBatch batch) {
        return ResponseEntity.accepted().location(URI.create("/api/v1/reconciliation/batches/" + batch.getId()))
                .body(BatchResponse.from(batch));
    }
}
