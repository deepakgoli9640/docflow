package com.hpg.payment.provider;

/** The provider rejected our credentials. A configuration problem: retrying will not help, alert operators. */
public class ProviderAuthenticationException extends ProviderException {

    public ProviderAuthenticationException(String operation, String message, Throwable cause) {
        super(operation, message, cause);
    }
}
