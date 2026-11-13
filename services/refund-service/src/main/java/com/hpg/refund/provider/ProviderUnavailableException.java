package com.hpg.refund.provider;

/** See {@link RefundProvider} for what this outcome means. */
public class ProviderUnavailableException extends RuntimeException {

    public ProviderUnavailableException(String operation, String message, Throwable cause) {
        super(operation + ": " + message, cause);
    }
}
