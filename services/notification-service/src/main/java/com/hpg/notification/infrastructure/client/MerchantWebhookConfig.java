package com.hpg.notification.infrastructure.client;

/**
 * What merchant-service hands back for signing a webhook.
 *
 * @param webhookSecret the per-merchant signing secret; {@link #toString()} masks it so it cannot reach
 *                      a log line through a careless interpolation
 */
public record MerchantWebhookConfig(String merchantId, String merchantStatus, String webhookUrl,
        String webhookSecret) {

    @Override
    public String toString() {
        return "MerchantWebhookConfig[merchantId=" + merchantId + ", status=" + merchantStatus
                + ", url=" + webhookUrl + ", webhookSecret=****]";
    }
}
