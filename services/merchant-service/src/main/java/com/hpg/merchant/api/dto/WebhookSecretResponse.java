package com.hpg.merchant.api.dto;

public record WebhookSecretResponse(String webhookSecret, String notice) {

    public static WebhookSecretResponse of(String secret) {
        return new WebhookSecretResponse(secret,
                "Use this secret to verify X-HPG-Signature on webhooks. It cannot be shown again.");
    }

    @Override
    public String toString() {
        return "WebhookSecretResponse[webhookSecret=****]";
    }
}
