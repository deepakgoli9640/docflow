package com.hpg.payment.api;

import com.hpg.payment.api.dto.CancelPaymentRequest;
import com.hpg.payment.api.dto.CreatePaymentRequest;
import com.hpg.payment.api.dto.PaymentResponse;
import com.hpg.payment.application.CreatePaymentCommand;
import com.hpg.payment.application.IdempotencyService;
import com.hpg.payment.application.PaymentCommandService;
import com.hpg.payment.application.PaymentCreationService;
import com.hpg.payment.application.PaymentProperties;
import com.hpg.payment.application.PaymentQueryService;
import com.hpg.payment.application.PaymentResult;
import com.hpg.payment.domain.Payment;
import com.hpg.payment.domain.PaymentStatus;
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
import org.springframework.format.annotation.DateTimeFormat;
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
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments", description = "Initiate, inspect, capture and cancel payments")
public class PaymentController {

    private static final Set<String> SORTABLE = Set.of("createdAt", "updatedAt", "amount", "status");

    private final PaymentCreationService creation;
    private final PaymentCommandService commands;
    private final PaymentQueryService queries;
    private final PaymentProperties properties;
    private final Clock clock;

    public PaymentController(PaymentCreationService creation, PaymentCommandService commands,
            PaymentQueryService queries, PaymentProperties properties, Clock clock) {
        this.creation = creation;
        this.commands = commands;
        this.queries = queries;
        this.properties = properties;
        this.clock = clock;
    }

    @PostMapping
    @Operation(summary = "Initiate a payment",
            description = "Requires Idempotency-Key. Returns 201 with a checkoutUrl for the patient. A repeat with the "
                    + "same key returns the current payment with header Idempotent-Replayed: true.")
    public ResponseEntity<PaymentResponse> create(MerchantPrincipal principal,
            @Parameter(required = true) @RequestHeader(value = IdempotencyService.HEADER, required = false) String key,
            @Valid @RequestBody CreatePaymentRequest request) {
        CreatePaymentRequest.Customer c = request.customer();
        PaymentResult result = creation.create(new CreatePaymentCommand(principal.merchantId(),
                request.merchantReference(), request.amount().toMoney(), request.description(),
                c == null ? null : new Payment.Customer(c.name(), c.email(), c.phone()), request.returnUrl(),
                request.expiresInMinutes()), key, request);
        return respond(result, URI.create("/api/v1/payments/" + result.payment().getId()));
    }

    @GetMapping("/{paymentId}")
    @Operation(summary = "Get a payment with its attempts and status history")
    public PaymentResponse get(MerchantPrincipal principal, @PathVariable UUID paymentId) {
        return PaymentResponse.details(queries.get(principal.merchantId(), paymentId), properties.checkoutBaseUrl(),
                clock.instant());
    }

    @GetMapping
    @Operation(summary = "List payments", description = "Filters: status, merchantReference, from/to (createdAt). "
            + "Sortable by createdAt, updatedAt, amount, status.")
    public PageResponse<PaymentResponse> list(MerchantPrincipal principal,
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(required = false) String merchantReference,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        SortValidator.requireAllowed(pageable, SORTABLE);
        Instant now = clock.instant();
        return PageResponse.from(queries.list(principal.merchantId(), status, merchantReference, from, to, pageable),
                p -> PaymentResponse.summary(p, properties.checkoutBaseUrl(), now));
    }

    @PostMapping("/{paymentId}/capture")
    @Operation(summary = "Capture an AUTHORIZED payment (full amount)",
            description = "200 CAPTURED; 202 UNKNOWN if the provider did not answer in time (verified automatically); "
                    + "422 if the provider refused; 503 if the provider is unavailable.")
    public ResponseEntity<PaymentResponse> capture(MerchantPrincipal principal, @PathVariable UUID paymentId,
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String key) {
        return respond(commands.capture(principal.merchantId(), paymentId, key), null);
    }

    @PostMapping("/{paymentId}/cancel")
    @Operation(summary = "Cancel a payment that has not been captured")
    public ResponseEntity<PaymentResponse> cancel(MerchantPrincipal principal, @PathVariable UUID paymentId,
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String key,
            @Valid @RequestBody(required = false) CancelPaymentRequest request) {
        return respond(commands.cancel(principal.merchantId(), paymentId, key, request == null ? null : request.reason()),
                null);
    }

    private ResponseEntity<PaymentResponse> respond(PaymentResult result, URI location) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.httpStatus());
        if (location != null && result.httpStatus() == 201) {
            builder.location(location);
        }
        if (result.replayed()) {
            builder.header("Idempotent-Replayed", "true");
        }
        return builder.body(PaymentResponse.summary(result.payment(), properties.checkoutBaseUrl(), clock.instant()));
    }
}
