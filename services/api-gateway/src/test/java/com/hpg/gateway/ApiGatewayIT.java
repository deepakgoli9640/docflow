package com.hpg.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gateway behaviour against a WireMock stand-in for merchant-service (which also serves the JWKS) and a
 * real Redis for rate limiting.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ApiGatewayIT {

    private static final String ISSUER = "http://issuer.test";

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static final WireMockServer MERCHANT_SERVICE = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    static RSAKey signingKey;
    static RSAKey foreignKey;

    @BeforeAll
    static void startStubs() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        foreignKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        MERCHANT_SERVICE.start();
    }

    @AfterAll
    static void stopStubs() {
        MERCHANT_SERVICE.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        if (!MERCHANT_SERVICE.isRunning()) {
            MERCHANT_SERVICE.start();
        }
        registry.add("HPG_MERCHANT_SERVICE_URI", MERCHANT_SERVICE::baseUrl);
        registry.add("HPG_PAYMENT_SERVICE_URI", MERCHANT_SERVICE::baseUrl); // same stub plays both services
        registry.add("HPG_RECON_SERVICE_URI", MERCHANT_SERVICE::baseUrl);
        registry.add("HPG_TOKEN_ISSUER", () -> ISSUER);
        registry.add("HPG_RATE_TOKEN_PER_SEC", () -> "1");
        registry.add("HPG_RATE_TOKEN_BURST", () -> "3");
        registry.add("HPG_GATEWAY_MANAGEMENT_PORT", () -> "0");
        registry.add("hpg.gateway.deny-list.consumer-enabled", () -> "false"); // no Kafka here; the list is set in Redis
    }

    @Autowired
    WebTestClient client;
    @Autowired
    com.hpg.gateway.denylist.MerchantDenyList denyList;

    @BeforeEach
    void stubMerchantService() {
        MERCHANT_SERVICE.resetAll();
        MERCHANT_SERVICE.stubFor(get(urlEqualTo("/oauth2/jwks")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(new JWKSet(signingKey.toPublicJWK()).toString())));
        MERCHANT_SERVICE.stubFor(any(anyUrl()).atPriority(10).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"ok\":true}")));
    }

    private static String token(RSAKey key, String issuer, List<String> roles, Instant expiresAt) throws Exception {
        return token(key, issuer, roles, expiresAt, UUID.randomUUID());
    }

    private static String token(RSAKey key, String issuer, List<String> roles, Instant expiresAt, UUID merchantId)
            throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer).subject("hpgk_test")
                .claim("roles", roles).claim("mid", merchantId.toString())
                .issueTime(new Date()).expirationTime(Date.from(expiresAt))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static String merchantToken() throws Exception {
        return token(signingKey, ISSUER, List.of("MERCHANT"), Instant.now().plusSeconds(600));
    }

    @Test
    void tokensOfASuspendedMerchantAreRefusedUntilReactivation() throws Exception {
        UUID merchantId = UUID.randomUUID();
        String token = token(signingKey, ISSUER, List.of("MERCHANT"), Instant.now().plusSeconds(600), merchantId);
        denyList.apply(merchantId, "SUSPENDED").block();

        client.get().uri("/api/v1/merchants/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.errorCode").isEqualTo("MERCHANT_SUSPENDED");
        MERCHANT_SERVICE.verify(0, getRequestedFor(urlEqualTo("/api/v1/merchants/me")));

        // Other merchants are unaffected.
        client.get().uri("/api/v1/merchants/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + merchantToken())
                .exchange().expectStatus().isOk();

        denyList.apply(merchantId, "ACTIVE").block();
        client.get().uri("/api/v1/merchants/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isOk();
    }

    @Test
    void opsTokensWithoutAMerchantIdPassTheDenyListCheck() throws Exception {
        // Real OPS and SERVICE tokens carry no "mid" claim.
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER).subject("ops-console").claim("roles", List.of("OPS"))
                .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(600)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(signingKey));
        client.get().uri("/api/v1/admin/merchants").header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.serialize())
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void publicRegistrationIsForwardedWithGeneratedCorrelationId() {
        client.post().uri("/api/v1/merchants").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Correlation-Id");
        MERCHANT_SERVICE.verify(postRequestedFor(urlEqualTo("/api/v1/merchants"))
                .withHeader("X-Correlation-Id", matching("[0-9a-f-]{36}")));
    }

    @Test
    void validClientCorrelationIdIsPreservedAndUnsafeOneReplaced() throws Exception {
        String token = merchantToken();
        client.get().uri("/api/v1/merchants/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-Correlation-Id", "his-req-77")
                .exchange()
                .expectHeader().valueEquals("X-Correlation-Id", "his-req-77");
        MERCHANT_SERVICE.verify(getRequestedFor(urlEqualTo("/api/v1/merchants/me"))
                .withHeader("X-Correlation-Id", equalTo("his-req-77")));

        client.get().uri("/api/v1/merchants/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-Correlation-Id", "bad id with spaces")
                .exchange()
                .expectHeader().value("X-Correlation-Id", v -> assertThat(v).doesNotContain(" "));
    }

    @Test
    void protectedRouteWithoutTokenIs401ProblemAndNeverReachesService() {
        client.get().uri("/api/v1/merchants/me")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("UNAUTHENTICATED")
                .jsonPath("$.correlationId").isNotEmpty();
        MERCHANT_SERVICE.verify(0, getRequestedFor(urlEqualTo("/api/v1/merchants/me")));
    }

    @Test
    void validTokenIsForwardedWithAuthorizationHeader() throws Exception {
        String token = merchantToken();
        client.get().uri("/api/v1/merchants/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk();
        MERCHANT_SERVICE.verify(getRequestedFor(urlEqualTo("/api/v1/merchants/me"))
                .withHeader("Authorization", equalTo("Bearer " + token)));
    }

    @Test
    void expiredTokenWrongIssuerAndForeignSignatureAreRejected() throws Exception {
        for (String bad : List.of(
                token(signingKey, ISSUER, List.of("MERCHANT"), Instant.now().minusSeconds(120)),
                token(signingKey, "http://evil.test", List.of("MERCHANT"), Instant.now().plusSeconds(600)),
                token(foreignKey, ISSUER, List.of("MERCHANT"), Instant.now().plusSeconds(600)))) {
            client.get().uri("/api/v1/merchants/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + bad)
                    .exchange()
                    .expectStatus().isUnauthorized();
        }
        MERCHANT_SERVICE.verify(0, getRequestedFor(urlEqualTo("/api/v1/merchants/me")));
    }

    @Test
    void adminRoutesRequireOpsRole() throws Exception {
        client.post().uri("/api/v1/admin/merchants/" + UUID.randomUUID() + "/activate")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + merchantToken())
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.errorCode").isEqualTo("FORBIDDEN");

        String ops = token(signingKey, ISSUER, List.of("OPS"), Instant.now().plusSeconds(600));
        client.get().uri("/api/v1/admin/merchants").header(HttpHeaders.AUTHORIZATION, "Bearer " + ops)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void internalEndpointsAreNotReachableThroughTheGateway() throws Exception {
        String service = token(signingKey, ISSUER, List.of("SERVICE"), Instant.now().plusSeconds(600));
        client.get().uri("/internal/v1/merchants/" + UUID.randomUUID() + "/webhook-config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + service)
                .exchange()
                .expectStatus().isForbidden();
        MERCHANT_SERVICE.verify(0, getRequestedFor(urlPathEqualTo("/internal/v1/merchants")));
    }

    @Test
    void unknownApiPathIs404Problem() throws Exception {
        client.get().uri("/api/v1/unknown").header(HttpHeaders.AUTHORIZATION, "Bearer " + merchantToken())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo("NOT_FOUND");
    }

    @Test
    void tokenEndpointIsRateLimitedPerClientIp() {
        int tooMany = 0;
        for (int i = 0; i < 8; i++) {
            HttpStatus status = HttpStatus.valueOf(client.post().uri("/oauth2/token")
                    .exchange().returnResult(String.class).getStatus().value());
            if (status == HttpStatus.TOO_MANY_REQUESTS) {
                tooMany++;
            }
        }
        assertThat(tooMany).as("requests beyond the burst of 3 are refused").isGreaterThanOrEqualTo(4);
    }

    @Test
    void checkoutIsPublicAndKeepsTheServicesContentSecurityPolicy() {
        MERCHANT_SERVICE.stubFor(get(urlEqualTo("/checkout/ck_abc")).willReturn(aResponse()
                .withHeader("Content-Type", "text/html")
                .withHeader("Content-Security-Policy", "script-src 'nonce-xyz'")
                .withBody("<html>checkout</html>")));
        client.get().uri("/checkout/ck_abc")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valuesMatch("Content-Security-Policy", "script-src 'nonce-xyz'");
    }

    @Test
    void providerWebhooksArePublicButSizeLimited() {
        client.post().uri("/webhooks/razorpay/payments").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"event\":\"payment.captured\"}")
                .exchange()
                .expectStatus().isOk();
        client.post().uri("/webhooks/razorpay/payments").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"pad\":\"" + "x".repeat(70_000) + "\"}")
                .exchange()
                .expectStatus().value(s -> assertThat(s).isIn(400, 413));
    }

    @Test
    void requestBodiesAreLimitedTo1MbExceptSettlementUploads() throws Exception {
        String ops = token(signingKey, ISSUER, List.of("OPS"), Instant.now().plusSeconds(600));
        String twoMb = "x".repeat(2 * 1024 * 1024);
        client.post().uri("/api/v1/payments").header(HttpHeaders.AUTHORIZATION, "Bearer " + merchantToken())
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"pad\":\"" + twoMb + "\"}")
                .exchange()
                .expectStatus().value(s -> assertThat(s).isIn(400, 413));
        client.post().uri("/api/v1/reconciliation/settlement-files").header(HttpHeaders.AUTHORIZATION, "Bearer " + ops)
                .contentType(MediaType.TEXT_PLAIN).bodyValue(twoMb)
                .exchange()
                .expectStatus().isOk();
        client.post().uri("/api/v1/reconciliation/settlement-files").header(HttpHeaders.AUTHORIZATION, "Bearer " + merchantToken())
                .contentType(MediaType.TEXT_PLAIN).bodyValue("x")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void paymentApiRequiresAToken() throws Exception {
        client.get().uri("/api/v1/payments").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/payments").header(HttpHeaders.AUTHORIZATION, "Bearer " + merchantToken())
                .exchange().expectStatus().isOk();
    }

    @Test
    void downstreamConnectionResetIs502Problem() throws Exception {
        String token = merchantToken();
        MERCHANT_SERVICE.stubFor(get(urlEqualTo("/api/v1/merchants/me")).willReturn(aResponse()
                .withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));
        client.get().uri("/api/v1/merchants/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectBody().jsonPath("$.errorCode").isEqualTo("BAD_GATEWAY");
    }
}
