package com.hpg.merchant.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.merchant.application.ApiKeyService;
import com.hpg.merchant.domain.CredentialStatus;
import com.hpg.merchant.infrastructure.persistence.MerchantCredentialRepository;
import com.hpg.merchant.support.ApiTestSupport;
import com.hpg.merchant.support.ApiTestSupport.RegisteredMerchant;
import com.hpg.merchant.support.PostgresTestContainer;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.hpg.merchant.support.ApiTestSupport.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests of merchant-service's HTTP API against a real PostgreSQL, including the OAuth2 token
 * endpoint and every authorization rule.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class MerchantApiIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate jdbc;

    ApiTestSupport api;

    @BeforeEach
    void setUp() {
        api = new ApiTestSupport(mvc, json);
    }

    private static String uniqueEmail() {
        return "billing+" + UUID.randomUUID() + "@hospital.example";
    }

    @Nested
    class Registration {

        @Test
        void returnsMerchantKeyAndWebhookSecretOnceAndStartsPending() throws Exception {
            mvc.perform(post("/api/v1/merchants").contentType(MediaType.APPLICATION_JSON).content("""
                            {"legalName":"City Hospital Pvt Ltd","displayName":"City Hospital",
                             "contactEmail":"%s","contactPhone":"+919800001234"}""".formatted(uniqueEmail())))
                    .andExpect(status().isCreated())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                    .andExpect(header().exists("X-Correlation-Id"))
                    .andExpect(jsonPath("$.merchant.status").value("PENDING_ACTIVATION"))
                    .andExpect(jsonPath("$.apiKey.keyId").value(org.hamcrest.Matchers.startsWith("hpgk_")))
                    .andExpect(jsonPath("$.apiKey.keySecret").isNotEmpty())
                    .andExpect(jsonPath("$.webhookSecret").value(org.hamcrest.Matchers.startsWith("whsec_")));
        }

        @Test
        void secretsAreNotStoredInPlaintext() throws Exception {
            RegisteredMerchant m = api.register(uniqueEmail());
            String hash = jdbc.queryForObject("SELECT secret_hash FROM merchant_credentials WHERE key_id = ?",
                    String.class, m.keyId());
            String webhook = jdbc.queryForObject("SELECT webhook_secret_enc FROM merchant_configurations WHERE merchant_id = ?",
                    String.class, m.id());
            assertThat(hash).startsWith("{bcrypt}").doesNotContain(m.keySecret());
            assertThat(webhook).startsWith("v1:").doesNotContain(m.webhookSecret());
        }

        @Test
        void duplicateEmailIsRejectedCaseInsensitively() throws Exception {
            String email = uniqueEmail();
            api.register(email);
            mvc.perform(post("/api/v1/merchants").contentType(MediaType.APPLICATION_JSON).content("""
                            {"legalName":"X","displayName":"X","contactEmail":"%s"}""".formatted(email.toUpperCase())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("MERCHANT_ALREADY_EXISTS"));
        }

        @Test
        void invalidRequestListsEveryViolation() throws Exception {
            mvc.perform(post("/api/v1/merchants").contentType(MediaType.APPLICATION_JSON).content("""
                            {"legalName":"","displayName":"X","contactEmail":"not-an-email","contactPhone":"12"}"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.violations[*].field", hasItem("legalName")))
                    .andExpect(jsonPath("$.violations[*].field", hasItem("contactEmail")))
                    .andExpect(jsonPath("$.violations[*].field", hasItem("contactPhone")));
        }

        @Test
        void registrationIsAudited() throws Exception {
            RegisteredMerchant m = api.register(uniqueEmail());
            List<String> actions = jdbc.queryForList(
                    "SELECT action FROM merchant_audit_log WHERE merchant_id = ? ORDER BY id", String.class, m.id());
            assertThat(actions).containsExactly("MERCHANT_REGISTERED");
        }
    }

    @Nested
    class Tokens {

        @Test
        void merchantTokenCarriesMerchantIdRoleAndShortExpiry() throws Exception {
            RegisteredMerchant m = api.register(uniqueEmail());
            SignedJWT jwt = SignedJWT.parse(api.token(m.keyId(), m.keySecret()));
            var claims = jwt.getJWTClaimsSet();
            assertThat(claims.getStringClaim("mid")).isEqualTo(m.id().toString());
            assertThat(claims.getStringListClaim("roles")).containsExactly("MERCHANT");
            assertThat(claims.getSubject()).isEqualTo(m.keyId());
            assertThat(claims.getIssuer()).isEqualTo("http://localhost");
            assertThat(claims.getExpirationTime().toInstant()).isBefore(Instant.now().plusSeconds(15 * 60 + 5));
            assertThat(jwt.getHeader().getKeyID()).isNotBlank();
        }

        @Test
        void wrongSecretIsRejected() throws Exception {
            RegisteredMerchant m = api.register(uniqueEmail());
            mvc.perform(post("/oauth2/token").with(httpBasic(m.keyId(), "wrong"))
                            .param("grant_type", "client_credentials"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void jwksPublishesOnlyThePublicKey() throws Exception {
            mvc.perform(get("/oauth2/jwks"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                    .andExpect(jsonPath("$.keys[0].d").doesNotExist());
        }

        @Test
        void tokenRequestRecordsKeyUsage() throws Exception {
            RegisteredMerchant m = api.register(uniqueEmail());
            api.token(m.keyId(), m.keySecret());
            assertThat(jdbc.queryForObject("SELECT last_used_at FROM merchant_credentials WHERE key_id = ?",
                    java.sql.Timestamp.class, m.keyId())).isNotNull();
        }
    }

    @Nested
    class Authorization {

        @Test
        void profileRequiresAToken() throws Exception {
            mvc.perform(get("/api/v1/merchants/me"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
        }

        @Test
        void garbageTokenIsRejected() throws Exception {
            mvc.perform(get("/api/v1/merchants/me").header(HttpHeaders.AUTHORIZATION, "Bearer abc.def.ghi"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void merchantCannotUseAdminEndpoints() throws Exception {
            RegisteredMerchant m = api.register(uniqueEmail());
            String token = api.token(m.keyId(), m.keySecret());
            mvc.perform(post("/api/v1/admin/merchants/{id}/activate", m.id())
                            .header(HttpHeaders.AUTHORIZATION, bearer(token))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"self-approve\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        }

        @Test
        void opsTokenIsNotAMerchantToken() throws Exception {
            mvc.perform(get("/api/v1/merchants/me").header(HttpHeaders.AUTHORIZATION, bearer(api.opsToken())))
                    .andExpect(status().isForbidden());
        }

        @Test
        void merchantCannotRevokeAnotherMerchantsKey() throws Exception {
            RegisteredMerchant a = api.register(uniqueEmail());
            RegisteredMerchant b = api.register(uniqueEmail());
            String tokenA = api.token(a.keyId(), a.keySecret());
            mvc.perform(delete("/api/v1/merchants/me/api-keys/{keyId}", b.keyId())
                            .header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("API_KEY_NOT_FOUND"));
        }

        @Test
        void internalEndpointRequiresServiceRole() throws Exception {
            RegisteredMerchant m = api.register(uniqueEmail());
            String merchantToken = api.token(m.keyId(), m.keySecret());
            mvc.perform(get("/internal/v1/merchants/{id}/webhook-config", m.id())
                            .header(HttpHeaders.AUTHORIZATION, bearer(merchantToken)))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/internal/v1/merchants/{id}/webhook-config", m.id())
                            .header(HttpHeaders.AUTHORIZATION, bearer(api.serviceToken())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.webhookSecret").value(m.webhookSecret()));
        }

        @Test
        void unknownPathsAreDeniedByDefault() throws Exception {
            RegisteredMerchant m = api.register(uniqueEmail());
            mvc.perform(get("/api/v1/something-new").header(HttpHeaders.AUTHORIZATION,
                            bearer(api.token(m.keyId(), m.keySecret()))))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    class Lifecycle {

        @Test
        void opsActivatesSuspendsAndReactivates() throws Exception {
            RegisteredMerchant m = api.register(uniqueEmail());
            String ops = api.opsToken();

            changeStatus(ops, m.id(), "activate").andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"));
            changeStatus(ops, m.id(), "activate").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_STATE_TRANSITION"));

            changeStatus(ops, m.id(), "suspend").andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SUSPENDED"));
            // A suspended merchant cannot obtain new tokens.
            mvc.perform(post("/oauth2/token").with(httpBasic(m.keyId(), m.keySecret()))
                            .param("grant_type", "client_credentials"))
                    .andExpect(status().isUnauthorized());

            changeStatus(ops, m.id(), "activate").andExpect(status().isOk());
            assertThat(api.token(m.keyId(), m.keySecret())).isNotBlank();

            List<String> actions = jdbc.queryForList(
                    "SELECT action FROM merchant_audit_log WHERE merchant_id = ? ORDER BY id", String.class, m.id());
            assertThat(actions).containsExactly("MERCHANT_REGISTERED", "MERCHANT_STATUS_CHANGED",
                    "MERCHANT_STATUS_CHANGED", "MERCHANT_STATUS_CHANGED");
        }

        @Test
        void statusChangeRequiresReason() throws Exception {
            RegisteredMerchant m = api.register(uniqueEmail());
            mvc.perform(post("/api/v1/admin/merchants/{id}/suspend", m.id())
                            .header(HttpHeaders.AUTHORIZATION, bearer(api.opsToken()))
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void unknownMerchantIs404() throws Exception {
            changeStatus(api.opsToken(), UUID.randomUUID(), "activate").andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("MERCHANT_NOT_FOUND"));
        }

        @Test
        void opsReadsAMerchantsConfigurationWithoutTheWebhookSecret() throws Exception {
            var m = api.register(uniqueEmail());
            String body = mvc.perform(get("/api/v1/admin/merchants/{id}/configuration", m.id())
                            .header(HttpHeaders.AUTHORIZATION, bearer(api.opsToken())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.captureMode").exists())
                    .andExpect(jsonPath("$.webhookSecretConfigured").value(true))
                    .andReturn().getResponse().getContentAsString();
            org.assertj.core.api.Assertions.assertThat(body).doesNotContain(m.webhookSecret());
            String merchantToken = api.token(m.keyId(), m.keySecret());
            mvc.perform(get("/api/v1/admin/merchants/{id}/configuration", m.id())
                            .header(HttpHeaders.AUTHORIZATION, bearer(merchantToken)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void adminListIsPaginatedAndRejectsUnknownSortFields() throws Exception {
            api.register(uniqueEmail());
            String ops = api.opsToken();
            mvc.perform(get("/api/v1/admin/merchants").param("size", "1").param("sort", "createdAt,desc")
                            .header(HttpHeaders.AUTHORIZATION, bearer(ops)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.page.size").value(1));
            mvc.perform(get("/api/v1/admin/merchants").param("sort", "contactEmail")
                            .header(HttpHeaders.AUTHORIZATION, bearer(ops)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_SORT_FIELD"));
        }

        private org.springframework.test.web.servlet.ResultActions changeStatus(String ops, UUID id, String action)
                throws Exception {
            return mvc.perform(post("/api/v1/admin/merchants/{id}/" + action, id)
                    .header(HttpHeaders.AUTHORIZATION, bearer(ops))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"test " + action + "\"}"));
        }
    }

    @Nested
    class Profile {

        @Test
        void merchantReadsAndPatchesOwnProfile() throws Exception {
            RegisteredMerchant m = api.register(uniqueEmail());
            String token = api.token(m.keyId(), m.keySecret());
            mvc.perform(patch("/api/v1/merchants/me").header(HttpHeaders.AUTHORIZATION, bearer(token))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"City Hospital East\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.displayName").value("City Hospital East"))
                    .andExpect(jsonPath("$.legalName").value("City Hospital Pvt Ltd"));
            mvc.perform(get("/api/v1/merchants/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(jsonPath("$.id").value(m.id().toString()))
                    .andExpect(jsonPath("$.displayName").value("City Hospital East"));
        }
    }

    @Nested
    class Configuration {

        private static final String VALID = """
                {"enabledMethods":["UPI","CARD"],"captureMode":"MANUAL",
                 "minAmount":{"amount":"10.00","currency":"INR"},
                 "maxAmountPerTransaction":{"amount":"150000.00","currency":"INR"},
                 "dailyLimit":{"amount":"2000000.00","currency":"INR"},
                 "paymentTtlMinutes":30,"maxAttempts":3,"refundWindowDays":90,
                 "webhookUrl":"https://his.cityhospital.example/hpg/webhooks",
                 "notifyPatientEmail":true,"notifyPatientSms":false}""";

        @Test
        void defaultsThenFullReplacement() throws Exception {
            RegisteredMerchant m = api.register(uniqueEmail());
            String token = api.token(m.keyId(), m.keySecret());
            mvc.perform(get("/api/v1/merchants/me/configuration").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.captureMode").value("AUTO"))
                    .andExpect(jsonPath("$.maxAmountPerTransaction.amount").value("200000.00"))
                    .andExpect(jsonPath("$.webhookSecretConfigured").value(true));

            mvc.perform(put("/api/v1/merchants/me/configuration").header(HttpHeaders.AUTHORIZATION, bearer(token))
                            .contentType(MediaType.APPLICATION_JSON).content(VALID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.captureMode").value("MANUAL"))
                    .andExpect(jsonPath("$.enabledMethods.length()").value(2))
                    .andExpect(jsonPath("$.maxAmountPerTransaction.amount").value("150000.00"))
                    .andExpect(jsonPath("$.webhookUrl").value("https://his.cityhospital.example/hpg/webhooks"));
        }

        @Test
        void rejectsLimitAbovePlatformMaximum() throws Exception {
            RegisteredMerchant m = api.register(uniqueEmail());
            mvc.perform(put("/api/v1/merchants/me/configuration")
                            .header(HttpHeaders.AUTHORIZATION, bearer(api.token(m.keyId(), m.keySecret())))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID.replace("150000.00", "900000.00")))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errorCode").value("LIMIT_EXCEEDS_PLATFORM_MAXIMUM"))
                    .andExpect(jsonPath("$.field").value("maxAmountPerTransaction"));
        }

        @Test
        void rejectsPrivateNetworkWebhook() throws Exception {
            RegisteredMerchant m = api.register(uniqueEmail());
            mvc.perform(put("/api/v1/merchants/me/configuration")
                            .header(HttpHeaders.AUTHORIZATION, bearer(api.token(m.keyId(), m.keySecret())))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID.replace("https://his.cityhospital.example/hpg/webhooks",
                                    "https://169.254.169.254/latest/meta-data")))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_WEBHOOK_URL"));
        }

        @Test
        void rejectsMoneyWithTooManyDecimals() throws Exception {
            RegisteredMerchant m = api.register(uniqueEmail());
            mvc.perform(put("/api/v1/merchants/me/configuration")
                            .header(HttpHeaders.AUTHORIZATION, bearer(api.token(m.keyId(), m.keySecret())))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID.replace("\"10.00\"", "\"10.001\"")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.violations[0].field").value("minAmount.amount"));
        }

        @Test
        void webhookSecretRotationChangesWhatNotificationServiceReads() throws Exception {
            RegisteredMerchant m = api.register(uniqueEmail());
            String token = api.token(m.keyId(), m.keySecret());
            String body = mvc.perform(post("/api/v1/merchants/me/webhook-secret/rotate")
                            .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String rotated = json.readTree(body).get("webhookSecret").asText();
            assertThat(rotated).isNotEqualTo(m.webhookSecret()).startsWith("whsec_");
            mvc.perform(get("/internal/v1/merchants/{id}/webhook-config", m.id())
                            .header(HttpHeaders.AUTHORIZATION, bearer(api.serviceToken())))
                    .andExpect(jsonPath("$.webhookSecret").value(rotated));
        }
    }

    @Nested
    class ApiKeys {

        @Autowired
        ApiKeyService apiKeyService;
        @Autowired
        MerchantCredentialRepository credentials;

        @Test
        void rotationFlowCreateSecondKeyRevokeFirst() throws Exception {
            RegisteredMerchant m = api.register(uniqueEmail());
            String token = api.token(m.keyId(), m.keySecret());

            String created = mvc.perform(post("/api/v1/merchants/me/api-keys").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            JsonNode second = json.readTree(created);

            // Third active key is refused.
            mvc.perform(post("/api/v1/merchants/me/api-keys").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("API_KEY_LIMIT_REACHED"));

            mvc.perform(delete("/api/v1/merchants/me/api-keys/{k}", m.keyId()).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isNoContent());
            // Revoking again is idempotent.
            mvc.perform(delete("/api/v1/merchants/me/api-keys/{k}", m.keyId()).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isNoContent());

            // Revoked key can no longer get tokens; the new one can.
            mvc.perform(post("/oauth2/token").with(httpBasic(m.keyId(), m.keySecret()))
                            .param("grant_type", "client_credentials"))
                    .andExpect(status().isUnauthorized());
            String newToken = api.token(second.get("keyId").asText(), second.get("keySecret").asText());

            // The last active key cannot be revoked.
            mvc.perform(delete("/api/v1/merchants/me/api-keys/{k}", second.get("keyId").asText())
                            .header(HttpHeaders.AUTHORIZATION, bearer(newToken)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("LAST_ACTIVE_API_KEY"));

            mvc.perform(get("/api/v1/merchants/me/api-keys").header(HttpHeaders.AUTHORIZATION, bearer(newToken)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[*].keySecret").doesNotExist());
        }

        /**
         * Ten concurrent "create key" calls for one merchant. Without the row lock, several could read
         * "1 active key" at the same moment and all insert, exceeding the limit of 2.
         */
        @Test
        void concurrentKeyCreationNeverExceedsTheLimit() throws Exception {
            RegisteredMerchant m = api.register(uniqueEmail());
            Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(m.keyId())
                    .claim("mid", m.id().toString()).build();
            int threads = 10;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<Boolean> task = () -> {
                    SecurityContextHolder.getContext().setAuthentication(
                            new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT"))));
                    start.await();
                    try {
                        apiKeyService.create(m.id());
                        return true;
                    } catch (com.hpg.common.error.BusinessException e) {
                        return false;
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                };
                results.add(pool.submit(task));
            }
            start.countDown();
            int succeeded = 0;
            for (Future<Boolean> f : results) {
                succeeded += f.get() ? 1 : 0;
            }
            pool.shutdown();
            assertThat(succeeded).isEqualTo(1);
            assertThat(credentials.countByMerchantIdAndStatus(m.id(), CredentialStatus.ACTIVE)).isEqualTo(2);
        }
    }

    @Test
    void correlationIdIsEchoedOnErrors() throws Exception {
        mvc.perform(get("/api/v1/merchants/me").header("X-Correlation-Id", "his-req-42"))
                .andExpect(header().string("X-Correlation-Id", "his-req-42"))
                .andExpect(jsonPath("$.correlationId").value("his-req-42"));
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }
}
