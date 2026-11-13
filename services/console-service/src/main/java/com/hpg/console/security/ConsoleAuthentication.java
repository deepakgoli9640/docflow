package com.hpg.console.security;

import com.hpg.console.client.ApiCredentials;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.io.Serial;

/**
 * A signed-in session: who they are, and the credentials the console uses on their behalf.
 *
 * <p>The credentials are kept in a field of their own rather than in {@code getCredentials()}, because
 * Spring Security erases that field after authentication — which would leave the session unable to
 * mint its next access token fifteen minutes later.
 */
public class ConsoleAuthentication extends AbstractAuthenticationToken {

    @Serial
    private static final long serialVersionUID = 1L;

    private final ConsoleUser user;
    private final ApiCredentials credentials;

    public ConsoleAuthentication(ConsoleUser user, ApiCredentials credentials) {
        super(user.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList());
        this.user = user;
        this.credentials = credentials;
        setAuthenticated(true);
    }

    public ApiCredentials credentials() {
        return credentials;
    }

    public ConsoleUser user() {
        return user;
    }

    @Override
    public Object getPrincipal() {
        return user;
    }

    /** Nothing here is a secret the framework needs: the real secret lives in {@link #credentials()}. */
    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public String getName() {
        return user.name();
    }
}
