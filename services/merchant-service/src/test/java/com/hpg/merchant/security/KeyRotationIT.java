package com.hpg.merchant.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.merchant.support.ApiTestSupport;
import com.hpg.merchant.support.PostgresTestContainer;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Signing-key rotation: the JWKS publishes the active key and the retired one, tokens are signed with the
 * active key only (named in the header), and tokens signed by the retired key are still accepted until they
 * expire.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class KeyRotationIT {

    static final Path DIR;
    static final KeyPair RETIRED;

    static {
        try {
            DIR = Files.createTempDirectory("hpg-jwt");
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair active = g.generateKeyPair();
            RETIRED = g.generateKeyPair();
            pem(DIR.resolve("private.pem"), "PRIVATE KEY", active.getPrivate().getEncoded());
            pem(DIR.resolve("public.pem"), "PUBLIC KEY", active.getPublic().getEncoded());
            pem(DIR.resolve("retired.pem"), "PUBLIC KEY", RETIRED.getPublic().getEncoded());
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static void pem(Path file, String type, byte[] der) throws Exception {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        Files.writeString(file, "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n");
    }

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry r) {
        r.add("hpg.security.jwt.private-key-location", () -> "file:" + DIR.resolve("private.pem"));
        r.add("hpg.security.jwt.public-key-location", () -> "file:" + DIR.resolve("public.pem"));
        r.add("hpg.security.jwt.previous-public-key-locations", () -> "file:" + DIR.resolve("retired.pem"));
        r.add("hpg.security.jwt.require-configured-key", () -> "true");
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    org.springframework.security.oauth2.jwt.JwtDecoder decoder;

    @Test
    void jwksHasActiveAndRetiredKeysAndTokensUseTheActiveOne() throws Exception {
        JsonNode jwks = json.readTree(mvc.perform(get("/oauth2/jwks")).andReturn().getResponse().getContentAsString());
        List<String> kids = new ArrayList<>();
        jwks.get("keys").forEach(k -> {
            kids.add(k.get("kid").asText());
            assertThat(k.has("d")).as("no private key material").isFalse();
        });
        assertThat(kids).hasSize(2).doesNotHaveDuplicates();

        ApiTestSupport api = new ApiTestSupport(mvc, json);
        String token = api.opsToken();
        String kid = SignedJWT.parse(token).getHeader().getKeyID();
        assertThat(kid).isEqualTo(kids.get(0));                       // the active key comes first
        assertThat(decoder.decode(token).getClaimAsStringList("roles")).containsExactly("OPS");
    }

    @Test
    void tokenSignedByTheRetiredKeyIsStillAccepted() throws Exception {
        String kid = new com.nimbusds.jose.jwk.RSAKey.Builder((java.security.interfaces.RSAPublicKey) RETIRED.getPublic())
                .keyIDFromThumbprint().build().getKeyID();
        com.nimbusds.jwt.JWTClaimsSet claims = new com.nimbusds.jwt.JWTClaimsSet.Builder()
                .issuer("http://localhost").subject("ops-console").claim("roles", List.of("OPS"))
                .issueTime(new java.util.Date()).expirationTime(new java.util.Date(System.currentTimeMillis() + 60_000)).build();
        SignedJWT jwt = new SignedJWT(new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.RS256)
                .keyID(kid).build(), claims);
        jwt.sign(new com.nimbusds.jose.crypto.RSASSASigner(RETIRED.getPrivate()));
        assertThat(decoder.decode(jwt.serialize()).getSubject()).isEqualTo("ops-console");
    }
}
