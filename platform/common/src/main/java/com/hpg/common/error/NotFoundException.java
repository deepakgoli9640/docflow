package com.hpg.common.error;

/**
 * Thrown when a resource does not exist <em>or does not belong to the caller</em>. Both cases return
 * the same 404 so a merchant cannot probe for the existence of another merchant's resources.
 */
public class NotFoundException extends BusinessException {

    public NotFoundException(ErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }

    public NotFoundException(String detail) {
        super(CommonErrorCode.NOT_FOUND, detail);
    }
}
