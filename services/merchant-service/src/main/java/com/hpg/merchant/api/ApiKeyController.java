package com.hpg.merchant.api;

import com.hpg.merchant.api.dto.ApiKeyCreatedResponse;
import com.hpg.merchant.api.dto.ApiKeyResponse;
import com.hpg.merchant.application.ApiKeyService;
import com.hpg.web.security.MerchantPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/merchants/me/api-keys")
@Tag(name = "API keys", description = "Create, list and revoke API keys (OAuth2 client credentials)")
public class ApiKeyController {

    private final ApiKeyService apiKeys;

    public ApiKeyController(ApiKeyService apiKeys) {
        this.apiKeys = apiKeys;
    }

    @PostMapping
    @Operation(summary = "Create an API key", description = "At most 2 active keys, to allow rotation. Secret shown once.")
    public ResponseEntity<ApiKeyCreatedResponse> create(MerchantPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiKeyCreatedResponse.from(apiKeys.create(principal.merchantId())));
    }

    @GetMapping
    @Operation(summary = "List my API keys (without secrets)")
    public List<ApiKeyResponse> list(MerchantPrincipal principal) {
        return apiKeys.list(principal.merchantId()).stream().map(ApiKeyResponse::from).toList();
    }

    @DeleteMapping("/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke an API key", description = "Idempotent. The last active key cannot be revoked.")
    public void revoke(MerchantPrincipal principal, @PathVariable String keyId) {
        apiKeys.revoke(principal.merchantId(), keyId);
    }
}
