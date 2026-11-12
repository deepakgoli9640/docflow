package com.hpg.merchant.application;

import com.hpg.merchant.domain.MerchantStatus;

import java.util.UUID;

public record WebhookConfig(UUID merchantId, MerchantStatus merchantStatus, String webhookUrl, String webhookSecret) {

    @Override
    public String toString() {
        return "WebhookConfig[merchantId=" + merchantId + ", status=" + merchantStatus + ", url=" + webhookUrl
                + ", secret=****]";
    }
}
