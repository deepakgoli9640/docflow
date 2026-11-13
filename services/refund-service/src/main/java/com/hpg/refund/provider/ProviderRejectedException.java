package com.hpg.refund.provider;

/** The provider refused the request; repeating it unchanged will not help. */
public class ProviderRejectedException extends RuntimeException {

    private final String code;
    private final String description;

    public ProviderRejectedException(String operation, String code, String description) {
        super(operation + " rejected: " + code + " " + description);
        this.code = code;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }
}
