package com.hpg.merchant.api.dto;

import com.hpg.merchant.application.WebhookConfig;
import com.hpg.merchant.domain.MerchantStatus;

import java.util.UUID;

public record InternalWebhookConfigResponse(UUID merchantId, MerchantStatus merchantStatus, String webhookUrl,
        String webhookSecret) {

    public static InternalWebhookConfigResponse from(WebhookConfig c) {
        return new InternalWebhookConfigResponse(c.merchantId(), c.merchantStatus(), c.webhookUrl(), c.webhookSecret());
    }

    @Override
    public String toString() {
        return "InternalWebhookConfigResponse[merchantId=" + merchantId + ", webhookSecret=****]";
    }
}
