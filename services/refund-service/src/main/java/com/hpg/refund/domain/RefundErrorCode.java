package com.hpg.refund.domain;

import com.hpg.common.error.ErrorCode;

public enum RefundErrorCode implements ErrorCode {

    REFUND_NOT_FOUND(404, "Refund not found"),
    PAYMENT_NOT_YET_REFUNDABLE(409, "Payment is not (yet) refundable"),
    PAYMENT_DISPUTED(409, "The payment is under an open dispute"),
    REQUEST_IN_PROGRESS(409, "A request with this Idempotency-Key is still being processed"),
    MERCHANT_SUSPENDED(403, "Merchant is not active"),
    PROVIDER_MISMATCH(409, "The payment was made with a provider this deployment cannot refund"),
    IDEMPOTENCY_KEY_REUSED(422, "Idempotency-Key was already used with a different request"),
    INVALID_IDEMPOTENCY_KEY(400, "Idempotency-Key header is invalid"),
    REFUND_EXCEEDS_CAPTURED_AMOUNT(422, "Refund exceeds refundable amount"),
    CURRENCY_MISMATCH(422, "Refund currency differs from the payment currency"),
    REFUND_WINDOW_EXPIRED(422, "The refund window for this payment has expired");

    private final int httpStatus;
    private final String title;

    RefundErrorCode(int httpStatus, String title) {
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
