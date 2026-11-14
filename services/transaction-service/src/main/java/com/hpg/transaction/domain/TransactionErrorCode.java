package com.hpg.transaction.domain;

import com.hpg.common.error.ErrorCode;

public enum TransactionErrorCode implements ErrorCode {

    TRANSACTION_NOT_FOUND(404, "Transaction not found"),
    INVALID_ACCOUNT(400, "Unknown ledger account"),
    INVALID_PERIOD(400, "Invalid reporting period"),
    DEAD_LETTER_NOT_FOUND(404, "Dead letter not found"),
    DEAD_LETTER_ALREADY_REPLAYED(409, "Dead letter already replayed");

    private final int httpStatus;
    private final String title;

    TransactionErrorCode(int httpStatus, String title) {
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
