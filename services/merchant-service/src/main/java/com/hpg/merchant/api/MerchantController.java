package com.hpg.merchant.api;

import com.hpg.merchant.api.dto.MerchantResponse;
import com.hpg.merchant.api.dto.RegisterMerchantRequest;
import com.hpg.merchant.api.dto.RegistrationResponse;
import com.hpg.merchant.api.dto.UpdateProfileRequest;
import com.hpg.merchant.application.MerchantOnboardingService;
import com.hpg.merchant.application.MerchantProfileService;
import com.hpg.merchant.application.RegisterMerchantCommand;
import com.hpg.merchant.application.RegistrationResult;
import com.hpg.web.security.MerchantPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/** Controllers only translate HTTP to application calls and entities to DTOs. No business rules here. */
@RestController
@RequestMapping("/api/v1/merchants")
@Tag(name = "Merchants", description = "Registration and the merchant's own profile")
public class MerchantController {

    private final MerchantOnboardingService onboarding;
    private final MerchantProfileService profiles;

    public MerchantController(MerchantOnboardingService onboarding, MerchantProfileService profiles) {
        this.onboarding = onboarding;
        this.profiles = profiles;
    }

    @PostMapping
    @Operation(summary = "Register a hospital", description = "Public. Returns the first API key and webhook "
            + "secret exactly once. The merchant starts in PENDING_ACTIVATION.")
    public ResponseEntity<RegistrationResponse> register(@Valid @RequestBody RegisterMerchantRequest request) {
        RegistrationResult result = onboarding.register(new RegisterMerchantCommand(request.legalName(),
                request.displayName(), request.contactEmail(), request.contactPhone()));
        return ResponseEntity.created(URI.create("/api/v1/merchants/me"))
                .cacheControl(CacheControl.noStore()) // response contains secrets
                .body(RegistrationResponse.from(result));
    }

    @GetMapping("/me")
    @Operation(summary = "Get my merchant profile")
    public MerchantResponse me(MerchantPrincipal principal) {
        return MerchantResponse.from(profiles.get(principal.merchantId()));
    }

    @PatchMapping("/me")
    @Operation(summary = "Update display name or contact phone")
    public MerchantResponse updateMe(MerchantPrincipal principal, @Valid @RequestBody UpdateProfileRequest request) {
        return MerchantResponse.from(profiles.update(principal.merchantId(), request.displayName(),
                request.contactPhone()));
    }
}
