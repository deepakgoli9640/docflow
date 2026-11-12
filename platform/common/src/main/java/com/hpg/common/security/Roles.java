package com.hpg.common.security;

/** Platform roles. See docs/09-security.md §3. */
public final class Roles {

    /** A hospital's server, acting for exactly one merchant. */
    public static final String MERCHANT = "MERCHANT";

    /** Internal operations / finance staff. */
    public static final String OPS = "OPS";

    /** Another HPG service calling an internal endpoint. */
    public static final String SERVICE = "SERVICE";

    private Roles() {
    }
}
