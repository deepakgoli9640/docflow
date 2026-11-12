package com.hpg.payment.domain;

import com.hpg.common.error.ErrorCode;

public enum PaymentErrorCode implements ErrorCode {

    PAYMENT_NOT_FOUND(404, "Payment not found"),
    INVALID_STATE_TRANSITION(409, "Operation not allowed in the payment's current state"),
    DUPLICATE_PAYMENT(409, "A live payment already exists for this merchant reference"),
    REQUEST_IN_PROGRESS(409, "A request with this Idempotency-Key is still being processed"),
    PAYMENT_ALREADY_CAPTURED(409, "The payment has already been captured"),
    IDEMPOTENCY_KEY_REUSED(422, "Idempotency-Key was already used with a different request"),
    INVALID_IDEMPOTENCY_KEY(400, "Idempotency-Key header is invalid"),
    MERCHANT_NOT_ACTIVE(403, "Merchant is not active"),
    AMOUNT_BELOW_MINIMUM(422, "Amount is below the merchant's minimum"),
    AMOUNT_ABOVE_LIMIT(422, "Amount exceeds the merchant's per-transaction limit"),
    DAILY_LIMIT_EXCEEDED(422, "Merchant's daily limit would be exceeded"),
    CURRENCY_NOT_SUPPORTED(422, "Currency is not supported for this merchant"),
    INVALID_EXPIRY(422, "Requested expiry exceeds the merchant's maximum payment lifetime"),
    INVALID_RETURN_URL(422, "Return URL is not allowed"),
    PROVIDER_REJECTED(422, "The payment provider rejected the request"),
    PROVIDER_UNAVAILABLE(503, "The payment provider is temporarily unavailable"),
    PROVIDER_CONFIGURATION_ERROR(502, "The payment provider rejected our credentials or configuration");

    private final int httpStatus;
    private final String title;

    PaymentErrorCode(int httpStatus, String title) {
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
