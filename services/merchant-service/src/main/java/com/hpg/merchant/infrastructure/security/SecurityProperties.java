package com.hpg.merchant.infrastructure.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * Token issuer settings (prefix {@code hpg.security}).
 *
 * @param issuer         value of the {@code iss} claim; resource servers validate it
 * @param accessTokenTtl lifetime of access tokens (short: limits damage of a leaked token)
 * @param jwt            signing key; if no key is configured an ephemeral one is generated (dev only)
 * @param staticClients  non-merchant clients (ops console, internal services) with secrets from env
 */
@Validated
@ConfigurationProperties(prefix = "hpg.security")
public record SecurityProperties(
        @NotBlank String issuer,
        @NotNull Duration accessTokenTtl,
        Jwt jwt,
        List<StaticClient> staticClients) {

    public SecurityProperties {
        staticClients = staticClients == null ? List.of() : List.copyOf(staticClients);
        jwt = jwt == null ? new Jwt(null, null) : jwt;
    }

    /** PEM files: PKCS#8 private key and X.509 SubjectPublicKeyInfo public key. */
    public record Jwt(Resource privateKeyLocation, Resource publicKeyLocation) {
    }

    public record StaticClient(String clientId, String clientSecret, List<String> roles) {

        @Override
        public String toString() {
            return "StaticClient[clientId=" + clientId + ", roles=" + roles + ", clientSecret=****]";
        }
    }
}
