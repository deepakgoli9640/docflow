package com.hpg.merchant.application;

import com.hpg.merchant.domain.Merchant;

public record RegistrationResult(Merchant merchant, IssuedApiKey apiKey, String webhookSecret) {

    @Override
    public String toString() {
        return "RegistrationResult[merchantId=" + merchant.getId() + ", apiKey=" + apiKey + ", webhookSecret=****]";
    }
}
