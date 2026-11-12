package com.hpg.merchant.api.dto;

import com.hpg.merchant.application.RegistrationResult;

public record RegistrationResponse(MerchantResponse merchant, ApiKeyCreatedResponse apiKey, String webhookSecret) {

    public static RegistrationResponse from(RegistrationResult result) {
        return new RegistrationResponse(MerchantResponse.from(result.merchant()),
                ApiKeyCreatedResponse.from(result.apiKey()), result.webhookSecret());
    }

    @Override
    public String toString() {
        return "RegistrationResponse[merchant=" + merchant.id() + ", apiKey=" + apiKey + ", webhookSecret=****]";
    }
}
