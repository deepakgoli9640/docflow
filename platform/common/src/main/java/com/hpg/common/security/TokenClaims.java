package com.hpg.common.security;

/** JWT claim names shared by the token issuer (merchant-service), the gateway and every resource server. */
public final class TokenClaims {

    /** Merchant id the token acts for. Absent on OPS and SERVICE tokens. */
    public static final String MERCHANT_ID = "mid";

    /** Role names without the {@code ROLE_} prefix, e.g. {@code ["MERCHANT"]}. */
    public static final String ROLES = "roles";

    private TokenClaims() {
    }
}
