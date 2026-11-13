package com.hpg.web.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Two trusted issuers: HPG's own tokens and the identity provider's (people), with strict rules for the latter. */
class HpgJwtDecodersTest {

    static final String HPG = "http://merchant-service:8081";
    static final String IDP = "https://app.hpg.example/auth/realms/hpg";
    static final KeyPair HPG_KEY = rsa();
    static final KeyPair IDP_KEY = rsa();

    final JwtDecoder decoder;

    HpgJwtDecodersTest() {
        NimbusJwtDecoder hpg = NimbusJwtDecoder.withPublicKey((RSAPublicKey) HPG_KEY.getPublic()).build();
        hpg.setJwtValidator(JwtValidators.createDefaultWithIssuer(HPG));
        NimbusJwtDecoder idp = NimbusJwtDecoder.withPublicKey((RSAPublicKey) IDP_KEY.getPublic()).build();
        idp.setJwtValidator(HpgJwtDecoders.externalValidator(new ExternalIssuerProperties(IDP, "unused", "hpg-api")));
        decoder = new HpgJwtDecoders.IssuerDispatchingJwtDecoder(Map.of(HPG, hpg, IDP, idp));
    }

    @Test
    void hpgTokensWorkAsBefore() {
        assertThat(decoder.decode(token(HPG_KEY, HPG, List.of("SERVICE"), null, null)).getClaimAsStringList("roles"))
                .containsExactly("SERVICE");
    }

    @Test
    void peopleFromTheIdentityProviderGetOpsOrTheirHospital() {
        assertThat(decoder.decode(token(IDP_KEY, IDP, List.of("OPS"), null, "hpg-api")).getIssuer().toString())
                .isEqualTo(IDP);
        String mid = UUID.randomUUID().toString();
        assertThat(decoder.decode(token(IDP_KEY, IDP, List.of("MERCHANT"), mid, "hpg-api")).getClaimAsString("mid"))
                .isEqualTo(mid);
    }

    @Test
    void providerSpecificRolesAreIgnored() {
        assertThat(decoder.decode(token(IDP_KEY, IDP, List.of("OPS", "offline_access", "default-roles-hpg"), null, "hpg-api"))
                .getClaimAsStringList("roles")).contains("OPS");
        assertRejected(token(IDP_KEY, IDP, List.of("offline_access"), null, "hpg-api"));
        assertRejected(token(IDP_KEY, IDP, List.of("OPS", "SERVICE"), null, "hpg-api"));
    }

    @Test
    void identityProviderCannotGrantServiceRoleOrAHospitalWithoutId() {
        assertRejected(token(IDP_KEY, IDP, List.of("SERVICE"), null, "hpg-api"));
        assertRejected(token(IDP_KEY, IDP, List.of("MERCHANT"), null, "hpg-api"));
        assertRejected(token(IDP_KEY, IDP, List.of("MERCHANT"), "not-a-uuid", "hpg-api"));
        assertRejected(token(IDP_KEY, IDP, List.of(), null, "hpg-api"));
    }

    @Test
    void tokensForAnotherApplicationAreRejected() {
        assertRejected(token(IDP_KEY, IDP, List.of("OPS"), null, "account"));
    }

    @Test
    void unknownIssuerAndCrossSignedTokensAreRejected() {
        assertRejected(token(IDP_KEY, "https://evil.example", List.of("OPS"), null, "hpg-api"));
        // Signed by the identity provider's key but claiming to be HPG (and vice versa)
        assertRejected(token(IDP_KEY, HPG, List.of("SERVICE"), null, null));
        assertRejected(token(HPG_KEY, IDP, List.of("OPS"), null, "hpg-api"));
        assertRejected("not.a.jwt");
    }

    private void assertRejected(String token) {
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    private static String token(KeyPair key, String issuer, List<String> roles, String mid, String audience) {
        try {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer(issuer).subject("someone")
                    .claim("roles", roles).issueTime(new Date())
                    .expirationTime(new Date(System.currentTimeMillis() + 60_000));
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
