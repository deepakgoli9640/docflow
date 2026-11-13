package com.hpg.notification.delivery;

import java.util.UUID;

/** Where and how to reach a hospital. The secret never travels over Kafka (docs/01 §4.2). */
public interface MerchantDirectory {

    WebhookConfig webhookConfig(UUID merchantId);

    record WebhookConfig(UUID merchantId, String merchantStatus, String webhookUrl, String webhookSecret) {

        @Override
        public String toString() {
            return "WebhookConfig[merchantId=" + merchantId + ", webhookUrl=" + webhookUrl + ", webhookSecret=****]";
        }
    }
}
