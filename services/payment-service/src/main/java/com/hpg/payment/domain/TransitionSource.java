package com.hpg.payment.domain;

/** Where a transition was triggered from. Stored in payment_status_history. */
public enum TransitionSource {
    MERCHANT_API(Authority.MERCHANT),
    PROVIDER_RESPONSE(Authority.PROVIDER),
    CHECKOUT_CALLBACK(Authority.PROVIDER),
    PROVIDER_WEBHOOK(Authority.PROVIDER),
    PROVIDER_POLL(Authority.PROVIDER),
    SYSTEM(Authority.SYSTEM);

    private final Authority authority;

    TransitionSource(Authority authority) {
        this.authority = authority;
    }

    public Authority authority() {
        return authority;
    }
}
