package com.hpg.reconciliation.domain;

import com.hpg.common.error.ErrorCode;

public enum ReconErrorCode implements ErrorCode {

    BATCH_NOT_FOUND(404, "Reconciliation batch not found"),
    RECORD_NOT_FOUND(404, "Reconciliation record not found"),
    DUPLICATE_SETTLEMENT_FILE(409, "This settlement file was already loaded"),
    NOT_A_DISCREPANCY(409, "Matched records have nothing to resolve"),
    INVALID_RESOLUTION_TRANSITION(409, "The record cannot move to that resolution state"),
    INVALID_SETTLEMENT_FILE(422, "The settlement file cannot be read"),
    SOURCE_UNAVAILABLE(422, "Provider API reconciliation is not available in this deployment"),
    INVALID_BUSINESS_DATE(422, "Invalid business date"),
    NO_COMPLETED_BATCH(404, "No completed reconciliation for that date"),
    PROVIDER_UNAVAILABLE(503, "The provider's settlement report could not be fetched");

    private final int httpStatus;
    private final String title;

    ReconErrorCode(int httpStatus, String title) {
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
