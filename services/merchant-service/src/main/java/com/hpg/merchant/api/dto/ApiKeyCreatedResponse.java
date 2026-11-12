package com.hpg.merchant.api.dto;

import com.hpg.merchant.application.IssuedApiKey;

import java.time.Instant;

public record ApiKeyCreatedResponse(String keyId, String keySecret, Instant createdAt, String notice) {

    public static ApiKeyCreatedResponse from(IssuedApiKey key) {
        return new ApiKeyCreatedResponse(key.keyId(), key.keySecret(), key.createdAt(),
                "Store keySecret securely now. It is not stored by HPG and cannot be shown again.");
    }

    @Override
    public String toString() {
        return "ApiKeyCreatedResponse[keyId=" + keyId + ", keySecret=****]";
    }
}
