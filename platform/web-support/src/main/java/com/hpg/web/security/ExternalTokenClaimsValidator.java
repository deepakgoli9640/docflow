package com.hpg.web.security;

import com.hpg.common.security.Roles;
import com.hpg.common.security.TokenClaims;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Limits what a token from the external identity provider may claim. People get MERCHANT (with the hospital
 * in {@code mid}) or OPS; never SERVICE or any other role. A misconfigured or compromised identity provider
 * therefore cannot mint machine credentials or a hospital token without a hospital.
 */
public class ExternalTokenClaimsValidator implements OAuth2TokenValidator<Jwt> {

    static final Set<String> ALLOWED_ROLES = Set.of(Roles.MERCHANT, Roles.OPS);

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(TokenClaims.ROLES);
        if (roles == null) {
            return fail("The token carries no HPG role.");
        }
        // Providers add roles of their own (Keycloak: offline_access, default-roles-...); only HPG's matter.
        if (roles.contains(Roles.SERVICE)) {
            return fail("The SERVICE role cannot be granted by the identity provider.");
        }
        if (roles.stream().noneMatch(ALLOWED_ROLES::contains)) {
            return fail("The token carries no HPG role.");
        }
        if (roles.contains(Roles.MERCHANT)) {
            String mid = jwt.getClaimAsString(TokenClaims.MERCHANT_ID);
            try {
                UUID.fromString(mid == null ? "" : mid);
            } catch (IllegalArgumentException e) {
                return fail("A hospital user's token must name the hospital (mid).");
            }
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static OAuth2TokenValidatorResult fail(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, description, null));
    }
}
