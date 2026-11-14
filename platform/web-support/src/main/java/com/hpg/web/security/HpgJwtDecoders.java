package com.hpg.web.security;

import com.nimbusds.jwt.JWTParser;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.text.ParseException;
import java.util.Collection;
import java.util.Map;

/**
 * Token decoding for the services: HPG's own tokens (merchant-service, API keys and internal clients) and,
 * when configured, the organisation's identity provider (people signing in to the dashboard).
 *
 * <p>The decoder is chosen by the token's {@code iss}, read <em>before</em> any verification only to pick the
 * key set; the chosen decoder then verifies signature, issuer, expiry and, for the external issuer, the
 * audience and the allowed claims. A token from any other issuer is rejected.
 */
public final class HpgJwtDecoders {

    private HpgJwtDecoders() {
    }

    /** Returns {@code primary} alone, or a decoder that also accepts the external issuer when it is configured. */
    public static JwtDecoder withExternalIssuer(JwtDecoder primary, String primaryIssuer, ExternalIssuerProperties external) {
        if (external == null || !external.enabled()) {
            return primary;
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(external.jwkSetUri()).build();
        decoder.setJwtValidator(externalValidator(external));
        return new IssuerDispatchingJwtDecoder(Map.of(primaryIssuer, primary, external.issuer(), decoder));
    }

    /** Issuer, expiry, audience and the allowed-claims rules for identity-provider tokens. */
    public static OAuth2TokenValidator<Jwt> externalValidator(ExternalIssuerProperties external) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(external.issuer()),
                new JwtClaimValidator<Collection<String>>("aud", aud -> aud != null && aud.contains(external.audience())),
                new ExternalTokenClaimsValidator());
    }

    /** Picks the decoder for the token's issuer. */
    public static final class IssuerDispatchingJwtDecoder implements JwtDecoder {

        private final Map<String, JwtDecoder> byIssuer;

        public IssuerDispatchingJwtDecoder(Map<String, JwtDecoder> byIssuer) {
            this.byIssuer = Map.copyOf(byIssuer);
        }

        @Override
        public Jwt decode(String token) throws JwtException {
            String issuer;
            try {
                issuer = JWTParser.parse(token).getJWTClaimsSet().getIssuer();
            } catch (ParseException e) {
                throw new BadJwtException("Malformed token", e);
            }
            JwtDecoder decoder = issuer == null ? null : byIssuer.get(issuer);
            if (decoder == null) {
                throw new BadJwtException("Untrusted token issuer");
            }
            return decoder.decode(token);
        }
    }
}
