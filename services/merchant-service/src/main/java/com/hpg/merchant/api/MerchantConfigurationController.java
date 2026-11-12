package com.hpg.merchant.api;

import com.hpg.merchant.api.dto.ConfigurationRequest;
import com.hpg.merchant.api.dto.ConfigurationResponse;
import com.hpg.merchant.api.dto.WebhookSecretResponse;
import com.hpg.merchant.application.MerchantConfigurationService;
import com.hpg.web.security.MerchantPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/merchants/me")
@Tag(name = "Merchant configuration", description = "Payment methods, limits, capture mode, webhooks")
public class MerchantConfigurationController {

    private final MerchantConfigurationService configurations;

    public MerchantConfigurationController(MerchantConfigurationService configurations) {
        this.configurations = configurations;
    }

    @GetMapping("/configuration")
    @Operation(summary = "Get my payment configuration")
    public ConfigurationResponse get(MerchantPrincipal principal) {
        return ConfigurationResponse.from(configurations.get(principal.merchantId()));
    }

    @PutMapping("/configuration")
    @Operation(summary = "Replace my payment configuration", description = "Values above platform maximums are rejected with 422.")
    public ConfigurationResponse update(MerchantPrincipal principal, @Valid @RequestBody ConfigurationRequest request) {
        return ConfigurationResponse.from(configurations.update(principal.merchantId(), request.toSettings()));
    }

    @PostMapping("/webhook-secret/rotate")
    @Operation(summary = "Rotate the webhook signing secret", description = "Returns the new secret once.")
    public ResponseEntity<WebhookSecretResponse> rotateWebhookSecret(MerchantPrincipal principal) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(WebhookSecretResponse.of(configurations.rotateWebhookSecret(principal.merchantId())));
    }
}
