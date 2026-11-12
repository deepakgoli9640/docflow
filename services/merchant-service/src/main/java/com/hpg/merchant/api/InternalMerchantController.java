package com.hpg.merchant.api;

import com.hpg.merchant.api.dto.InternalWebhookConfigResponse;
import com.hpg.merchant.application.MerchantConfigurationService;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Service-to-service endpoint (SERVICE role). Not routed by the api-gateway. This is the single
 * synchronous internal call in the architecture: notification-service needs the webhook signing secret,
 * which must not be published to Kafka (see docs/01-architecture-overview.md §4.2).
 */
@Hidden
@RestController
@RequestMapping("/internal/v1/merchants")
public class InternalMerchantController {

    private final MerchantConfigurationService configurations;

    public InternalMerchantController(MerchantConfigurationService configurations) {
        this.configurations = configurations;
    }

    @GetMapping("/{merchantId}/webhook-config")
    public ResponseEntity<InternalWebhookConfigResponse> webhookConfig(@PathVariable UUID merchantId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(InternalWebhookConfigResponse.from(configurations.webhookConfig(merchantId)));
    }
}
