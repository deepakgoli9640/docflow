package com.hpg.gateway.config;

import com.hpg.common.security.Roles;
import com.hpg.common.security.TokenClaims;
import com.nimbusds.jwt.JWTParser;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;

import java.text.ParseException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Chooses the key set by the token's {@code iss} (read unverified, only to pick the decoder); the chosen
 * decoder verifies everything. Tokens from any other issuer are rejected. Mirrors
 * {@code com.hpg.web.security.HpgJwtDecoders} in the servlet services.
 */
public final class IssuerDispatchingReactiveJwtDecoder implements ReactiveJwtDecoder {

    private final Map<String, ReactiveJwtDecoder> byIssuer;

    public IssuerDispatchingReactiveJwtDecoder(Map<String, ReactiveJwtDecoder> byIssuer) {
        this.byIssuer = Map.copyOf(byIssuer);
    }

    @Override
    public Mono<Jwt> decode(String token) {
        String issuer;
        try {
            issuer = JWTParser.parse(token).getJWTClaimsSet().getIssuer();
        } catch (ParseException e) {
            return Mono.error(new BadJwtException("Malformed token", e));
        }
        ReactiveJwtDecoder decoder = issuer == null ? null : byIssuer.get(issuer);
        return decoder == null ? Mono.error(new BadJwtException("Untrusted token issuer")) : decoder.decode(token);
    }

    /** Issuer, expiry, audience; people get MERCHANT (with a hospital) or OPS, never SERVICE. */
    static OAuth2TokenValidator<Jwt> externalValidator(IdentityProviderProperties idp) {
        Set<String> allowed = Set.of(Roles.MERCHANT, Roles.OPS);
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            List<String> roles = jwt.getClaimAsStringList(TokenClaims.ROLES);
            // Providers add roles of their own (Keycloak: offline_access, ...); only HPG's matter, never SERVICE.
            if (roles == null || roles.contains(Roles.SERVICE) || roles.stream().noneMatch(allowed::contains)) {
                return failure("Roles " + roles + " grant no access, or include one the identity provider cannot grant.");
            }
            if (roles.contains(Roles.MERCHANT)) {
                try {
                    UUID.fromString(String.valueOf(jwt.getClaimAsString(TokenClaims.MERCHANT_ID)));
                } catch (IllegalArgumentException e) {
                    return failure("A hospital user's token must name the hospital (mid).");
                }
            }
            return OAuth2TokenValidatorResult.success();
        };
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(idp.issuer()),
                new JwtClaimValidator<Collection<String>>("aud", aud -> aud != null && aud.contains(idp.audience())),
                claims);
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, description, null));
    }
}
