package com.hpg.persistence;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Who is acting: the token subject (API key id, ops client, service), or a fixed label. */
public final class Actors {

    public static final String ANONYMOUS = "anonymous";
    public static final String SYSTEM = "system";

    private Actors() {
    }

    /** The authenticated principal's name; "anonymous" for public requests; "system" for jobs/consumers. */
    public static String current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return SYSTEM;
        }
        if (!auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return ANONYMOUS;
        }
        return auth.getName();
    }
}
