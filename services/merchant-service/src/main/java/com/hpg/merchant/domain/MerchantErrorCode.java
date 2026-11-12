package com.hpg.merchant.domain;

import com.hpg.common.error.ErrorCode;

public enum MerchantErrorCode implements ErrorCode {

    MERCHANT_NOT_FOUND(404, "Merchant not found"),
    MERCHANT_ALREADY_EXISTS(409, "A merchant with this contact email already exists"),
    INVALID_STATE_TRANSITION(409, "Invalid merchant status transition"),
    MERCHANT_DEACTIVATED(409, "Merchant is deactivated"),
    API_KEY_NOT_FOUND(404, "API key not found"),
    API_KEY_LIMIT_REACHED(409, "Maximum number of active API keys reached"),
    LAST_ACTIVE_API_KEY(409, "Cannot revoke the last active API key"),
    LIMIT_EXCEEDS_PLATFORM_MAXIMUM(422, "Configured value exceeds the platform maximum"),
    INVALID_LIMITS(422, "Configured limits are inconsistent"),
    UNSUPPORTED_CURRENCY(422, "Currency is not supported"),
    INVALID_WEBHOOK_URL(422, "Webhook URL is not allowed");

    private final int httpStatus;
    private final String title;

    MerchantErrorCode(int httpStatus, String title) {
        this.httpStatus = httpStatus;
        this.title = title;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }

    @Override
    public String title() {
        return title;
    }
}
