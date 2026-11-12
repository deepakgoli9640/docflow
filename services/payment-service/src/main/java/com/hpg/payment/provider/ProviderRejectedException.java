package com.hpg.payment.provider;

/** The provider understood and refused the request (4xx). Not retryable as-is. */
public class ProviderRejectedException extends ProviderException {

    private final String providerErrorCode;
    private final String providerDescription;

    public ProviderRejectedException(String operation, String providerErrorCode, String providerDescription) {
        super(operation, providerErrorCode + " - " + providerDescription, null);
        this.providerErrorCode = providerErrorCode;
        this.providerDescription = providerDescription;
    }

    public String providerErrorCode() {
        return providerErrorCode;
    }

    public String providerDescription() {
        return providerDescription;
    }
}
