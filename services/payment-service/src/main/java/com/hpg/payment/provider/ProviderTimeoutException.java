package com.hpg.payment.provider;

/** The request was sent but no answer arrived in time: it may or may not have taken effect. Verify before retrying anything that moves money. */
public class ProviderTimeoutException extends ProviderException {

    public ProviderTimeoutException(String operation, String message, Throwable cause) {
        super(operation, message, cause);
    }
}
