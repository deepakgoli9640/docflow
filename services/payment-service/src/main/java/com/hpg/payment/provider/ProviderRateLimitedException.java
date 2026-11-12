package com.hpg.payment.provider;

/** HTTP 429 from the provider. The request was not processed; retry after backing off. */
public class ProviderRateLimitedException extends ProviderUnavailableException {

    public ProviderRateLimitedException(String operation, String message) {
        super(operation, message, null);
    }
}
