package com.hpg.gateway.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import reactor.test.StepVerifier;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

class IssuerDispatchingReactiveJwtDecoderTest {

    static final String HPG = "http://merchant-service:8081";
    static final String IDP = "http://localhost:8090/auth/realms/hpg";
    static final KeyPair HPG_KEY = rsa();
    static final KeyPair IDP_KEY = rsa();

    final IssuerDispatchingReactiveJwtDecoder decoder;

    IssuerDispatchingReactiveJwtDecoderTest() {
        NimbusReactiveJwtDecoder hpg = NimbusReactiveJwtDecoder.withPublicKey((RSAPublicKey) HPG_KEY.getPublic()).build();
        hpg.setJwtValidator(JwtValidators.createDefaultWithIssuer(HPG));
        NimbusReactiveJwtDecoder idp = NimbusReactiveJwtDecoder.withPublicKey((RSAPublicKey) IDP_KEY.getPublic()).build();
        idp.setJwtValidator(IssuerDispatchingReactiveJwtDecoder.externalValidator(
                new IdentityProviderProperties(IDP, "unused", "hpg-api")));
        decoder = new IssuerDispatchingReactiveJwtDecoder(Map.of(HPG, hpg, IDP, idp));
    }

    @Test
    void acceptsBothIssuersWithTheirRules() {
        StepVerifier.create(decoder.decode(token(HPG_KEY, HPG, List.of("MERCHANT"), UUID.randomUUID().toString(), null)))
                .expectNextCount(1).verifyComplete();
        StepVerifier.create(decoder.decode(token(IDP_KEY, IDP, List.of("OPS"), null, "hpg-api")))
                .expectNextCount(1).verifyComplete();
    }

    @Test
    void rejectsServiceRoleFromTheIdentityProviderUnknownIssuersAndWrongAudience() {
        StepVerifier.create(decoder.decode(token(IDP_KEY, IDP, List.of("SERVICE"), null, "hpg-api")))
                .expectError(JwtException.class).verify();
        StepVerifier.create(decoder.decode(token(IDP_KEY, IDP, List.of("MERCHANT"), null, "hpg-api")))
                .expectError(JwtException.class).verify();
        StepVerifier.create(decoder.decode(token(IDP_KEY, IDP, List.of("OPS"), null, "account")))
                .expectError(JwtException.class).verify();
        StepVerifier.create(decoder.decode(token(IDP_KEY, "https://evil.example", List.of("OPS"), null, "hpg-api")))
                .expectError(JwtException.class).verify();
    }

    private static String token(KeyPair key, String issuer, List<String> roles, String mid, String audience) {
        try {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer(issuer).subject("someone")
                    .claim("roles", roles).issueTime(new Date()).expirationTime(new Date(System.currentTimeMillis() + 60_000));
            if (mid != null) {
                claims.claim("mid", mid);
            }
            if (audience != null) {
                claims.audience(audience);
            }
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
            jwt.sign(new RSASSASigner(key.getPrivate()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair rsa() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
