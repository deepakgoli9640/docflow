package com.hpg.merchant.api.dto;

import com.hpg.merchant.domain.Merchant;
import com.hpg.merchant.domain.MerchantStatus;

import java.time.Instant;
import java.util.UUID;

public record MerchantResponse(
        UUID id,
        String legalName,
        String displayName,
        String contactEmail,
        String contactPhone,
        MerchantStatus status,
        String statusReason,
        Instant createdAt,
        Instant updatedAt) {

    public static MerchantResponse from(Merchant m) {
        return new MerchantResponse(m.getId(), m.getLegalName(), m.getDisplayName(), m.getContactEmail(),
                m.getContactPhone(), m.getStatus(), m.getStatusReason(), m.getCreatedAt(), m.getUpdatedAt());
    }
}
