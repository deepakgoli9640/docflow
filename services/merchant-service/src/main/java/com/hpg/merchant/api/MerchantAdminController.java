package com.hpg.merchant.api;

import com.hpg.merchant.api.dto.ConfigurationResponse;
import com.hpg.merchant.application.MerchantConfigurationService;

import com.hpg.merchant.api.dto.MerchantResponse;
import com.hpg.merchant.api.dto.StatusChangeRequest;
import com.hpg.merchant.application.MerchantOnboardingService;
import com.hpg.merchant.domain.MerchantStatus;
import com.hpg.web.paging.PageResponse;
import com.hpg.web.paging.SortValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/merchants")
@Tag(name = "Merchant administration (OPS)", description = "Activation, suspension and listing")
public class MerchantAdminController {

    private static final Set<String> SORTABLE = Set.of("createdAt", "updatedAt", "legalName", "status");

    private final MerchantOnboardingService onboarding;
    private final MerchantConfigurationService configurations;

    public MerchantAdminController(MerchantOnboardingService onboarding, MerchantConfigurationService configurations) {
        this.onboarding = onboarding;
        this.configurations = configurations;
    }

    @GetMapping
    @Operation(summary = "List merchants", description = "Sortable by: createdAt, updatedAt, legalName, status")
    public PageResponse<MerchantResponse> list(@RequestParam(required = false) MerchantStatus status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        SortValidator.requireAllowed(pageable, SORTABLE);
        return PageResponse.from(onboarding.list(status, pageable), MerchantResponse::from);
    }

    @GetMapping("/{merchantId}")
    @Operation(summary = "Get a merchant")
    public MerchantResponse get(@PathVariable UUID merchantId) {
        return MerchantResponse.from(onboarding.get(merchantId));
    }

    @GetMapping("/{merchantId}/configuration")
    @Operation(summary = "Get a merchant's payment configuration", description = "Never includes the webhook secret.")
    public ConfigurationResponse configuration(@PathVariable UUID merchantId) {
        return ConfigurationResponse.from(configurations.getForOps(merchantId));
    }

    @PostMapping("/{merchantId}/activate")
    @Operation(summary = "Activate (or reactivate a suspended) merchant")
    public MerchantResponse activate(@PathVariable UUID merchantId, @Valid @RequestBody StatusChangeRequest request) {
        return MerchantResponse.from(onboarding.activate(merchantId, request.reason()));
    }

    @PostMapping("/{merchantId}/suspend")
    @Operation(summary = "Suspend a merchant", description = "New tokens are refused immediately.")
    public MerchantResponse suspend(@PathVariable UUID merchantId, @Valid @RequestBody StatusChangeRequest request) {
        return MerchantResponse.from(onboarding.suspend(merchantId, request.reason()));
    }

    @PostMapping("/{merchantId}/deactivate")
    @Operation(summary = "Permanently deactivate a merchant")
    public MerchantResponse deactivate(@PathVariable UUID merchantId, @Valid @RequestBody StatusChangeRequest request) {
        return MerchantResponse.from(onboarding.deactivate(merchantId, request.reason()));
    }
}
