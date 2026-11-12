package com.hpg.payment.provider;

/** Base type for provider failures. Subtypes encode what the caller may safely do next. */
public abstract class ProviderException extends RuntimeException {

    private final String operation;

    protected ProviderException(String operation, String message, Throwable cause) {
        super(operation + ": " + message, cause);
        this.operation = operation;
    }

    public String operation() {
        return operation;
    }
}
