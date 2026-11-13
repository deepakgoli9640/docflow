package com.hpg.console.security;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * Who is signed in, as far as the console is concerned.
 *
 * @param name       the client id that signed in (an API key id, or {@code ops-console})
 * @param roles      roles exactly as the access token states them; the console never adds any
 * @param merchantId set when a hospital signed in, null for operations
 */
public record ConsoleUser(String name, List<String> roles, String merchantId) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public ConsoleUser {
        roles = List.copyOf(roles);
    }

    public boolean isMerchant() {
        return merchantId != null;
    }

    public boolean isOps() {
        return roles.contains("OPS");
    }

    /** What to show in the header: an API key id is long and meaningless to a person. */
    public String displayName() {
        return isMerchant() ? "Hospital" : "Operations";
    }
}
