package com.hpg.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The organisation's identity provider (dashboard single sign-on), trusted next to merchant-service.
 * Same meaning as {@code hpg.security.oidc} in the services; off when {@code issuer} is blank.
 */
@ConfigurationProperties(prefix = "hpg.gateway.oidc")
public record IdentityProviderProperties(String issuer, String jwkSetUri, @DefaultValue("hpg-api") String audience) {

    public boolean enabled() {
        return issuer != null && !issuer.isBlank();
    }
}
