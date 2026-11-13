package com.hpg.refund.provider;

/** See {@link RefundProvider} for what this outcome means. */
public class ProviderTimeoutException extends RuntimeException {

    public ProviderTimeoutException(String operation, String message, Throwable cause) {
        super(operation + ": " + message, cause);
    }
}
