package com.hpg.web.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * A second token issuer the services trust: the organisation's identity provider (Keycloak or any OpenID
 * Connect provider) that signs people in to the dashboard. Prefix {@code hpg.security.oidc}; off when
 * {@code issuer} is blank.
 *
 * @param issuer    exact {@code iss} of its tokens, e.g. {@code https://app.example.com/auth/realms/hpg}
 * @param jwkSetUri where its signing keys are published (may be an internal URL)
 * @param audience  required {@code aud} entry, so tokens issued for other applications are refused
 */
@ConfigurationProperties("hpg.security.oidc")
public record ExternalIssuerProperties(String issuer, String jwkSetUri, @DefaultValue("hpg-api") String audience) {

    public boolean enabled() {
        return issuer != null && !issuer.isBlank();
    }
}
