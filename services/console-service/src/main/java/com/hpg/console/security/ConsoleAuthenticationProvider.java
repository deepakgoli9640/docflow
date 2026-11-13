package com.hpg.console.security;

import com.hpg.common.security.TokenClaims;
import com.hpg.console.client.ApiCredentials;
import com.hpg.console.client.TokenClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.util.List;

/**
 * Signing in <em>is</em> a token exchange.
 *
 * <p>The console has no user table. It takes what was typed, asks the gateway for an access token with
 * it, and if a token comes back the credentials were good — and the token itself says which role the
 * person has. Nothing can drift out of step with merchant-service, because merchant-service is the only
 * thing deciding.
 *
 * <p>The token is then <b>verified</b> against the published signing keys before its claims are
 * trusted. It arrived over our own call to the gateway, so this is belt and braces, but reading claims
 * out of an unverified JWT is the kind of shortcut that quietly becomes a vulnerability.
 *
 * <p><b>Demo-grade simplification.</b> A hospital signs in with an API key id and secret, and operations
 * sign in with the shared {@code ops-console} client secret. In a real deployment, people would have
 * their own accounts — staff behind the hospital's identity provider, operators behind the company's,
 * with multi-factor authentication — and API keys would stay where they belong: in servers, never typed
 * into a browser. That is a user-management feature, not a console feature, so it is written down here
 * rather than half-built.
 */
public class ConsoleAuthenticationProvider implements AuthenticationProvider {

    private static final Logger log = LoggerFactory.getLogger(ConsoleAuthenticationProvider.class);

    private final TokenClient tokenClient;
    private final JwtDecoder jwtDecoder;

    public ConsoleAuthenticationProvider(TokenClient tokenClient, JwtDecoder jwtDecoder) {
        this.tokenClient = tokenClient;
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String clientId = String.valueOf(authentication.getPrincipal()).trim();
        String clientSecret = String.valueOf(authentication.getCredentials());
        if (clientId.isEmpty() || clientSecret.isEmpty()) {
            throw new BadCredentialsException("Enter both an id and a secret");
        }

        TokenClient.Token token;
        try {
            token = tokenClient.exchange(clientId, clientSecret);
        } catch (TokenClient.InvalidCredentialsException e) {
            // Wrong secret, revoked key, or suspended merchant: the console is not told which, and must
            // not guess — saying "this key is revoked" would confirm the key exists.
            throw new BadCredentialsException("Those credentials were not accepted");
        } catch (RuntimeException e) {
            log.error("Could not reach the gateway to sign in", e);
            throw new AuthenticationServiceException("The gateway is unavailable, please try again");
        }

        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(token.value());
        } catch (JwtException e) {
            log.error("The gateway issued a token this console cannot verify", e);
            throw new AuthenticationServiceException("The issued token could not be verified");
        }

        List<String> roles = jwt.getClaimAsStringList(TokenClaims.ROLES);
        if (roles == null || roles.isEmpty()) {
            throw new BadCredentialsException("These credentials carry no role");
        }
        ConsoleUser user = new ConsoleUser(jwt.getSubject(), roles, jwt.getClaimAsString(TokenClaims.MERCHANT_ID));
        log.info("Console sign-in subject={} roles={} merchant={}", user.name(), roles, user.merchantId() != null);
        return new ConsoleAuthentication(user, new ApiCredentials(clientId, clientSecret, tokenClient));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
