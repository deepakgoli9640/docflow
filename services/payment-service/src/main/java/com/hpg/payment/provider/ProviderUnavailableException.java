package com.hpg.payment.provider;

/** The request did not reach the provider or the provider could not process it (5xx, connection refused, circuit open). Safe to retry. */
public class ProviderUnavailableException extends ProviderException {

    public ProviderUnavailableException(String operation, String message, Throwable cause) {
        super(operation, message, cause);
    }
}
