package com.hpg.merchant.application;

import com.hpg.common.error.NotFoundException;
import com.hpg.merchant.domain.Merchant;
import com.hpg.merchant.domain.MerchantErrorCode;
import com.hpg.merchant.infrastructure.persistence.MerchantRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
class MerchantLookup {

    private final MerchantRepository merchants;

    MerchantLookup(MerchantRepository merchants) {
        this.merchants = merchants;
    }

    Merchant require(UUID merchantId) {
        return merchants.findById(merchantId).orElseThrow(() -> notFound(merchantId));
    }

    Merchant requireForUpdate(UUID merchantId) {
        return merchants.findByIdForUpdate(merchantId).orElseThrow(() -> notFound(merchantId));
    }

    private static NotFoundException notFound(UUID merchantId) {
        return new NotFoundException(MerchantErrorCode.MERCHANT_NOT_FOUND, "Merchant " + merchantId + " not found.");
    }
}
