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
import com.hpg.events.Topics;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.awaitility.Awaitility.await;

/**
 * The edge refusing a suspended merchant.
 *
 * <p>Access tokens live for fifteen minutes and are verified by signature, so revoking one is not
 * possible. Instead merchant-service publishes the new status, the gateway records it in Redis, and the
 * next request is refused — which closes the window between "suspended" and "token expires".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.kafka.listener.auto-startup=true")
@Testcontainers
class MerchantAccessIT {

    private static final String ISSUER = "http://issuer.test";

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    static final WireMockServer MERCHANT_SERVICE = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    static RSAKey signingKey;

    @BeforeAll
    static void startStubs() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        MERCHANT_SERVICE.start();
        MERCHANT_SERVICE.stubFor(get(urlEqualTo("/oauth2/jwks")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(new JWKSet(signingKey.toPublicJWK()).toString())));
        MERCHANT_SERVICE.stubFor(any(anyUrl()).atPriority(10).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"ok\":true}")));
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
        registry.add("HPG_PAYMENT_SERVICE_URI", MERCHANT_SERVICE::baseUrl);
        registry.add("HPG_TOKEN_ISSUER", () -> ISSUER);
        registry.add("HPG_GATEWAY_MANAGEMENT_PORT", () -> "0");
    }

    @Autowired
    WebTestClient client;
    @Autowired
    ReactiveStringRedisTemplate redis;
    @Autowired
    KafkaConnectionDetails kafkaConnection;

    KafkaProducer<String, String> producer;

    @BeforeEach
    void setUp() {
        producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaConnection.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()));
    }

    private String tokenFor(UUID merchantId) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER).subject("hpgk_test")
                .claim("roles", List.of("MERCHANT")).claim("mid", merchantId.toString())
                .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(900)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                claims);
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    private void publishStatus(UUID merchantId, String status) {
        String event = """
                {"eventId":"%s","eventType":"MerchantSnapshot","eventVersion":1,"occurredAt":"%s",
                 "aggregateId":"%s","merchantId":"%s",
                 "payload":{"merchantId":"%s","version":1,"status":"%s","displayName":"City Hospital"}}"""
                .formatted(UUID.randomUUID(), Instant.now(), merchantId, merchantId, merchantId, status);
        try {
            producer.send(new ProducerRecord<>(Topics.MERCHANT_SNAPSHOTS, merchantId.toString(), event))
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            producer.flush();
        }
    }

    private void awaitRecorded(UUID merchantId, String status) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                org.assertj.core.api.Assertions.assertThat(
                                redis.opsForHash().get("hpg:merchant-access", merchantId.toString()).block())
                        .isEqualTo(status));
    }

    @Test
    void aSuspendedMerchantIsRefusedEvenWithAValidToken() throws Exception {
        UUID merchantId = UUID.randomUUID();
        String token = tokenFor(merchantId);

        // Active: the request goes through.
        publishStatus(merchantId, "ACTIVE");
        awaitRecorded(merchantId, "ACTIVE");
        client.get().uri("/api/v1/payments").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isOk();

        // Suspended: the same unexpired token is now refused at the edge.
        publishStatus(merchantId, "SUSPENDED");
        awaitRecorded(merchantId, "SUSPENDED");
        client.get().uri("/api/v1/payments").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("FORBIDDEN")
                .jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("suspended"));

        // Reactivated: allowed again, without the merchant having to fetch a new token.
        publishStatus(merchantId, "ACTIVE");
        awaitRecorded(merchantId, "ACTIVE");
        client.get().uri("/api/v1/payments").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isOk();
    }

    @Test
    void aMerchantTheGatewayHasNotHeardAboutIsAllowedThrough() throws Exception {
        // Fail-open: the services check merchant status themselves, and a gap in this list must not stop
        // payments. A merchant the gateway has no record of is passed on.
        client.get().uri("/api/v1/payments")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(UUID.randomUUID()))
                .exchange().expectStatus().isOk();
    }

    @Test
    void deactivatedIsAlsoRefused() throws Exception {
        UUID merchantId = UUID.randomUUID();
        publishStatus(merchantId, "DEACTIVATED");
        awaitRecorded(merchantId, "DEACTIVATED");
        client.get().uri("/api/v1/payments").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(merchantId))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void aPendingMerchantMayStillUseTheApi() throws Exception {
        // A merchant that has registered but is not activated can configure itself and read its profile;
        // it just cannot take payments, which payment-service enforces.
        UUID merchantId = UUID.randomUUID();
        publishStatus(merchantId, "PENDING_ACTIVATION");
        awaitRecorded(merchantId, "PENDING_ACTIVATION");
        client.get().uri("/api/v1/payments").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(merchantId))
                .exchange().expectStatus().isOk();
    }
}
