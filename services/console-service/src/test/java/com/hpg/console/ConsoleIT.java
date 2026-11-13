package com.hpg.console;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The console against a WireMock stand-in for the gateway.
 *
 * <p>The console holds no data of its own, so what is worth testing is the behaviour that only exists
 * here: who may see which pages, that the browser never receives a token or a secret, that CSRF
 * protection is on, that every state-changing call carries an idempotency key, and that an API refusal
 * is shown in the service's own words instead of a stack trace.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConsoleIT {

    private static final String ISSUER = "http://issuer.test";
    private static final String MERCHANT_KEY = "hpgk_consoleTestKey000001";
    private static final String MERCHANT_SECRET = "merchant-secret-value";
    private static final String OPS_SECRET = "ops-secret-value";
    private static final UUID MERCHANT_ID = UUID.fromString("b1e7c3d2-0000-7000-8000-000000000001");
    private static final String PAYMENT_ID = "3f9c2a51-0000-7000-8000-000000000002";

    static final WireMockServer GATEWAY = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    static RSAKey signingKey;

    @BeforeAll
    static void startGateway() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("console-test").generate();
        GATEWAY.start();
    }

    @AfterAll
    static void stopGateway() {
        GATEWAY.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        if (!GATEWAY.isRunning()) {
            GATEWAY.start();
        }
        registry.add("hpg.console.gateway-uri", GATEWAY::baseUrl);
        registry.add("hpg.security.issuer", () -> ISSUER);
    }

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void stubGateway() {
        GATEWAY.resetAll();
        GATEWAY.stubFor(WireMock.get(urlEqualTo("/oauth2/jwks")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(new JWKSet(signingKey.toPublicJWK()).toString())));
        // The token endpoint accepts our two test clients and refuses everything else.
        GATEWAY.stubFor(WireMock.post(urlEqualTo("/oauth2/token"))
                .withBasicAuth(MERCHANT_KEY, MERCHANT_SECRET)
                .willReturn(okJson(tokenResponse(token(MERCHANT_KEY, List.of("MERCHANT"), MERCHANT_ID.toString())))));
        GATEWAY.stubFor(WireMock.post(urlEqualTo("/oauth2/token"))
                .withBasicAuth("ops-console", OPS_SECRET)
                .willReturn(okJson(tokenResponse(token("ops-console", List.of("OPS"), null)))));
        GATEWAY.stubFor(WireMock.post(urlEqualTo("/oauth2/token")).atPriority(10)
                .willReturn(aResponse().withStatus(401)));
    }

    private static String token(String subject, List<String> roles, String merchantId) {
        try {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .issuer(ISSUER).subject(subject).claim("roles", roles)
                    .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(900)));
            if (merchantId != null) {
                claims.claim("mid", merchantId);
            }
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims.build());
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String tokenResponse(String accessToken) {
        return "{\"access_token\":\"" + accessToken + "\",\"token_type\":\"Bearer\",\"expires_in\":900}";
    }

    /** Signs in through the real form-login path and keeps the session for subsequent requests. */
    private MockHttpSession signIn(String clientId, String secret) throws Exception {
        MvcResult result = mvc.perform(formLogin("/login").user("username", clientId).password("password", secret))
                .andExpect(authenticated())
                .andExpect(redirectedUrl("/"))
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private MockHttpSession merchantSession() throws Exception {
        return signIn(MERCHANT_KEY, MERCHANT_SECRET);
    }

    private MockHttpSession opsSession() throws Exception {
        return signIn("ops-console", OPS_SECRET);
    }

    private static String paymentJson(String status) {
        return """
                {"id":"%s","merchantReference":"BILL-2026-000981","status":"%s",
                 "amount":{"amount":"20000.00","currency":"INR"},"captureMode":"MANUAL",
                 "description":"Patient share","checkoutUrl":"http://localhost:8080/checkout/ck_abc",
                 "expiresAt":"2026-11-13T11:00:00Z","createdAt":"2026-11-13T10:00:00Z",
                 "updatedAt":"2026-11-13T10:00:05Z",
                 "customer":{"name":"A Patient","email":"p*****t@example.com","phone":"+91******1234"},
                 "attempts":[{"providerPaymentId":"pay_sim_1","method":"upi","status":"AUTHORIZED",
                   "duplicateCapture":false,"createdAt":"2026-11-13T10:00:04Z"}],
                 "statusHistory":[{"to":"CREATED","source":"MERCHANT_API","at":"2026-11-13T10:00:00Z"},
                   {"from":"CREATED","to":"PENDING","source":"PROVIDER_RESPONSE","at":"2026-11-13T10:00:01Z"}]}"""
                .formatted(PAYMENT_ID, status);
    }

    private void stubPaymentsList(String status) {
        GATEWAY.stubFor(WireMock.get(urlPathEqualTo("/api/v1/payments")).willReturn(okJson("""
                {"content":[%s],"page":{"number":0,"size":20,"totalElements":1,"totalPages":1}}"""
                .formatted(paymentJson(status)))));
    }

    // ---------------------------------------------------------------- access

    @Nested
    class Access {

        @Test
        void theLoginPageIsPublicAndExplainsWhatToType() throws Exception {
            mvc.perform(get("/login"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Sign in")))
                    .andExpect(content().string(containsString("hpgk_")));
        }

        @Test
        void everythingElseNeedsASession() throws Exception {
            for (String path : List.of("/", "/payments", "/settings", "/api-keys", "/ops/merchants",
                    "/ops/notifications")) {
                mvc.perform(get(path))
                        .andExpect(status().is3xxRedirection())
                        .andExpect(redirectedUrlPattern("**/login"));
            }
        }

        @Test
        void wrongCredentialsStayOnTheLoginPage() throws Exception {
            mvc.perform(formLogin("/login").user("username", MERCHANT_KEY).password("password", "wrong"))
                    .andExpect(unauthenticated())
                    .andExpect(redirectedUrl("/login?error"));
        }

        @Test
        void aHospitalCannotOpenTheOperationsPages() throws Exception {
            MockHttpSession session = merchantSession();
            mvc.perform(get("/ops/merchants").session(session)).andExpect(status().isForbidden());
            mvc.perform(get("/ops/notifications").session(session)).andExpect(status().isForbidden());
        }

        @Test
        void operationsCannotOpenTheHospitalPages() throws Exception {
            // Operations hold no merchant id, so there is no "own payments" for them to look at.
            MockHttpSession session = opsSession();
            mvc.perform(get("/payments").session(session)).andExpect(status().isForbidden());
            mvc.perform(get("/api-keys").session(session)).andExpect(status().isForbidden());
        }

        @Test
        void eachRoleLandsOnItsOwnWork() throws Exception {
            mvc.perform(get("/").session(merchantSession())).andExpect(redirectedUrl("/payments"));
            mvc.perform(get("/").session(opsSession())).andExpect(redirectedUrl("/ops/merchants"));
        }

        @Test
        void signingOutEndsTheSession() throws Exception {
            MockHttpSession session = merchantSession();
            mvc.perform(post("/logout").session(session).with(csrf()))
                    .andExpect(redirectedUrl("/login?loggedOut"));
            mvc.perform(get("/payments").session(session)).andExpect(redirectedUrlPattern("**/login"));
        }

        @Test
        void aFormPostWithoutACsrfTokenIsRefused() throws Exception {
            // Without this, any other site could submit "capture this payment" as the signed-in person.
            mvc.perform(post("/payments/{id}/capture", PAYMENT_ID).session(merchantSession()))
                    .andExpect(status().isForbidden());
            GATEWAY.verify(0, postRequestedFor(urlPathEqualTo("/api/v1/payments/" + PAYMENT_ID + "/capture")));
        }
    }

    // ---------------------------------------------------------------- the browser holds no secrets

    @Nested
    class SecretHandling {

        @Test
        void noTokenOrSecretIsEverSentToTheBrowser() throws Exception {
            stubPaymentsList("PENDING");
            MvcResult result = mvc.perform(get("/payments").session(merchantSession()))
                    .andExpect(status().isOk()).andReturn();
            String html = result.getResponse().getContentAsString();
            assertThat(html).doesNotContain(MERCHANT_SECRET).doesNotContain("eyJ");  // no JWT either
            assertThat(result.getResponse().getHeaderNames())
                    .noneSatisfy(name -> assertThat(name).isEqualToIgnoringCase("Authorization"));
        }

        @Test
        void theSessionCookieIsNotReadableFromJavaScript() throws Exception {
            MvcResult result = mvc.perform(formLogin("/login").user("username", MERCHANT_KEY)
                    .password("password", MERCHANT_SECRET)).andReturn();
            assertThat(result.getResponse().getCookies())
                    .filteredOn(cookie -> "JSESSIONID".equals(cookie.getName()))
                    .allSatisfy(cookie -> assertThat(cookie.isHttpOnly()).isTrue());
        }

        @Test
        void aNewApiKeySecretIsShownOnceAndCopyable() throws Exception {
            GATEWAY.stubFor(WireMock.post(urlEqualTo("/api/v1/merchants/me/api-keys")).willReturn(okJson("""
                    {"keyId":"hpgk_newKey00000000000001","keySecret":"the-new-secret",
                     "createdAt":"2026-11-13T10:00:00Z","notice":"Store keySecret securely now."}""")));
            GATEWAY.stubFor(WireMock.get(urlEqualTo("/api/v1/merchants/me/api-keys")).willReturn(okJson("""
                    [{"keyId":"hpgk_newKey00000000000001","status":"ACTIVE","createdAt":"2026-11-13T10:00:00Z"}]""")));
            MockHttpSession session = merchantSession();

            mvc.perform(post("/api-keys").session(session).with(csrf()))
                    .andExpect(redirectedUrl("/api-keys"));
            // The secret appears on the page it redirects to, once, and nowhere afterwards.
            mvc.perform(get("/api-keys").session(session))
                    .andExpect(content().string(containsString("the-new-secret")))
                    .andExpect(content().string(containsString("Shown once")));
            mvc.perform(get("/api-keys").session(session))
                    .andExpect(content().string(org.hamcrest.Matchers.not(containsString("the-new-secret"))));
        }

        @Test
        void aStrictContentSecurityPolicyIsSet() throws Exception {
            mvc.perform(get("/login"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .header().string("Content-Security-Policy", containsString("script-src 'self'")));
        }
    }

    // ---------------------------------------------------------------- hospital pages

    @Nested
    class HospitalPages {

        @Test
        void thePaymentListShowsWhatTheApiReturned() throws Exception {
            stubPaymentsList("CAPTURED");
            mvc.perform(get("/payments").session(merchantSession()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("BILL-2026-000981")))
                    .andExpect(content().string(containsString("INR 20000.00")))
                    .andExpect(content().string(containsString("CAPTURED")));
        }

        @Test
        void thePaymentDetailShowsAttemptsAndHowItGotThere() throws Exception {
            GATEWAY.stubFor(WireMock.get(urlEqualTo("/api/v1/payments/" + PAYMENT_ID))
                    .willReturn(okJson(paymentJson("AUTHORIZED"))));
            mvc.perform(get("/payments/{id}", PAYMENT_ID).session(merchantSession()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("pay_sim_1")))
                    .andExpect(content().string(containsString("PROVIDER_RESPONSE")))
                    .andExpect(content().string(containsString("Capture")))
                    // Masked by the API, and shown masked here.
                    .andExpect(content().string(containsString("p*****t@example.com")));
        }

        @Test
        void creatingAPaymentSendsTheApiAnIdempotencyKey() throws Exception {
            GATEWAY.stubFor(WireMock.post(urlEqualTo("/api/v1/payments")).willReturn(aResponse().withStatus(201)
                    .withHeader("Content-Type", "application/json").withBody(paymentJson("PENDING"))));

            mvc.perform(post("/payments").session(merchantSession()).with(csrf())
                            .param("merchantReference", "BILL-2026-000981")
                            .param("amount", "20000.00")
                            .param("customerEmail", "patient@example.com"))
                    .andExpect(redirectedUrl("/payments/" + PAYMENT_ID));

            GATEWAY.verify(postRequestedFor(urlEqualTo("/api/v1/payments"))
                    .withHeader("Idempotency-Key", matching("[0-9a-f-]{36}"))
                    .withRequestBody(equalToJson("""
                            {"merchantReference":"BILL-2026-000981",
                             "amount":{"amount":"20000.00","currency":"INR"},
                             "customer":{"email":"patient@example.com"}}""", true, true)));
        }

        @Test
        void theFormIsCheckedBeforeBotheringTheApi() throws Exception {
            mvc.perform(post("/payments").session(merchantSession()).with(csrf())
                            .param("merchantReference", "")
                            .param("amount", "-5"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("New payment")));
            GATEWAY.verify(0, postRequestedFor(urlEqualTo("/api/v1/payments")));
        }

        @Test
        void anApiRefusalIsShownInTheServicesOwnWords() throws Exception {
            GATEWAY.stubFor(WireMock.post(urlEqualTo("/api/v1/payments")).willReturn(aResponse().withStatus(422)
                    .withHeader("Content-Type", "application/problem+json")
                    .withBody("""
                            {"type":"https://docs.hpg.example/errors/daily-limit-exceeded","title":"Limit",
                             "status":422,"errorCode":"DAILY_LIMIT_EXCEEDED",
                             "detail":"This payment would exceed the daily limit of 50000.00 INR.",
                             "remainingToday":{"amount":"20000.00","currency":"INR"}}""")));

            mvc.perform(post("/payments").session(merchantSession()).with(csrf())
                            .param("merchantReference", "BILL-X").param("amount", "60000.00"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("DAILY_LIMIT_EXCEEDED")))
                    .andExpect(content().string(containsString("exceed the daily limit of 50000.00 INR")));
        }

        @Test
        void aCaptureThatTimedOutIsNotReportedAsSuccess() throws Exception {
            // 202 with status UNKNOWN: the provider did not answer. Telling the hospital "captured" would
            // be a lie, and "failed" would be worse, because the money may well have moved.
            GATEWAY.stubFor(WireMock.post(urlEqualTo("/api/v1/payments/" + PAYMENT_ID + "/capture"))
                    .willReturn(aResponse().withStatus(202).withHeader("Content-Type", "application/json")
                            .withBody(paymentJson("UNKNOWN"))));
            GATEWAY.stubFor(WireMock.get(urlEqualTo("/api/v1/payments/" + PAYMENT_ID))
                    .willReturn(okJson(paymentJson("UNKNOWN"))));
            MockHttpSession session = merchantSession();

            mvc.perform(post("/payments/{id}/capture", PAYMENT_ID).session(session).with(csrf()))
                    .andExpect(redirectedUrl("/payments/" + PAYMENT_ID));
            mvc.perform(get("/payments/{id}", PAYMENT_ID).session(session))
                    .andExpect(content().string(containsString("did not answer in time")))
                    .andExpect(content().string(containsString("do not capture again")));
        }

        @Test
        void theLiveFragmentIsJustTheTable() throws Exception {
            stubPaymentsList("PENDING");
            String fragment = mvc.perform(get("/payments/table").session(merchantSession()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(fragment).contains("BILL-2026-000981").doesNotContain("<html").doesNotContain("Sign out");
        }

        @Test
        void aListWithNothingInFlightDoesNotPollAtAll() throws Exception {
            stubPaymentsList("CAPTURED");
            assertThat(mvc.perform(get("/payments").session(merchantSession())).andReturn().getResponse()
                    .getContentAsString()).contains("data-live-active=\"false\"");

            stubPaymentsList("PENDING");
            assertThat(mvc.perform(get("/payments").session(merchantSession())).andReturn().getResponse()
                    .getContentAsString()).contains("data-live-active=\"true\"");
        }

        @Test
        void settingsShowsTheConfigurationAndSavesItWhole() throws Exception {
            GATEWAY.stubFor(WireMock.get(urlEqualTo("/api/v1/merchants/me")).willReturn(okJson("""
                    {"id":"%s","legalName":"City Hospital Pvt Ltd","displayName":"City Hospital",
                     "contactEmail":"billing@city.example","status":"ACTIVE",
                     "createdAt":"2026-11-13T09:00:00Z","updatedAt":"2026-11-13T09:00:00Z"}"""
                    .formatted(MERCHANT_ID))));
            GATEWAY.stubFor(WireMock.get(urlEqualTo("/api/v1/merchants/me/configuration")).willReturn(okJson("""
                    {"enabledMethods":["UPI","CARD"],"captureMode":"MANUAL",
                     "minAmount":{"amount":"1.00","currency":"INR"},
                     "maxAmountPerTransaction":{"amount":"150000.00","currency":"INR"},
                     "dailyLimit":{"amount":"2000000.00","currency":"INR"},
                     "paymentTtlMinutes":30,"maxAttempts":3,"refundWindowDays":90,
                     "webhookUrl":"https://his.example/hooks","webhookSecretConfigured":true,
                     "notifyPatientEmail":true,"notifyPatientSms":false,"updatedAt":"2026-11-13T09:00:00Z"}""")));
            GATEWAY.stubFor(put(urlEqualTo("/api/v1/merchants/me/configuration")).willReturn(okJson("{}")));
            MockHttpSession session = merchantSession();

            mvc.perform(get("/settings").session(session))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("City Hospital")))
                    .andExpect(content().string(containsString("https://his.example/hooks")))
                    .andExpect(content().string(containsString("150000.00")));

            mvc.perform(post("/settings/configuration").session(session).with(csrf())
                            .param("enabledMethods", "UPI")
                            .param("captureMode", "AUTO")
                            .param("minAmount", "10.00")
                            .param("maxAmountPerTransaction", "100000.00")
                            .param("dailyLimit", "1000000.00")
                            .param("paymentTtlMinutes", "45")
                            .param("maxAttempts", "2")
                            .param("refundWindowDays", "60")
                            .param("webhookUrl", "https://his.example/hooks")
                            .param("notifyPatientEmail", "on"))
                    .andExpect(redirectedUrl("/settings"));

            GATEWAY.verify(putRequestedFor(urlEqualTo("/api/v1/merchants/me/configuration"))
                    .withRequestBody(equalToJson("""
                            {"enabledMethods":["UPI"],"captureMode":"AUTO",
                             "minAmount":{"amount":"10.00","currency":"INR"},
                             "maxAmountPerTransaction":{"amount":"100000.00","currency":"INR"},
                             "dailyLimit":{"amount":"1000000.00","currency":"INR"},
                             "paymentTtlMinutes":45,"maxAttempts":2,"refundWindowDays":60,
                             "webhookUrl":"https://his.example/hooks",
                             "notifyPatientEmail":true,"notifyPatientSms":false}""", true, true)));
        }

        @Test
        void revokingTheLastKeyShowsWhyTheApiSaidNo() throws Exception {
            GATEWAY.stubFor(delete(urlEqualTo("/api/v1/merchants/me/api-keys/hpgk_only"))
                    .willReturn(aResponse().withStatus(409)
                            .withHeader("Content-Type", "application/problem+json")
                            .withBody("""
                                    {"status":409,"errorCode":"LAST_ACTIVE_API_KEY",
                                     "detail":"Create a replacement key before revoking the last active one."}""")));
            GATEWAY.stubFor(WireMock.get(urlEqualTo("/api/v1/merchants/me/api-keys")).willReturn(okJson("""
                    [{"keyId":"hpgk_only","status":"ACTIVE","createdAt":"2026-11-13T09:00:00Z"}]""")));
            MockHttpSession session = merchantSession();

            mvc.perform(post("/api-keys/{id}/revoke", "hpgk_only").session(session).with(csrf()))
                    .andExpect(redirectedUrl("/api-keys"));
            mvc.perform(get("/api-keys").session(session))
                    .andExpect(content().string(containsString("Create a replacement key")));
            GATEWAY.verify(deleteRequestedFor(urlEqualTo("/api/v1/merchants/me/api-keys/hpgk_only")));
        }
    }

    // ---------------------------------------------------------------- operations pages

    @Nested
    class OperationsPages {

        @Test
        void theMerchantListAndDetailRender() throws Exception {
            GATEWAY.stubFor(WireMock.get(urlPathEqualTo("/api/v1/admin/merchants")).willReturn(okJson("""
                    {"content":[{"id":"%s","legalName":"City Hospital Pvt Ltd","displayName":"City Hospital",
                      "contactEmail":"billing@city.example","status":"PENDING_ACTIVATION",
                      "createdAt":"2026-11-13T09:00:00Z","updatedAt":"2026-11-13T09:00:00Z"}],
                     "page":{"number":0,"size":20,"totalElements":1,"totalPages":1}}""".formatted(MERCHANT_ID))));
            GATEWAY.stubFor(WireMock.get(urlEqualTo("/api/v1/admin/merchants/" + MERCHANT_ID)).willReturn(okJson("""
                    {"id":"%s","legalName":"City Hospital Pvt Ltd","displayName":"City Hospital",
                     "contactEmail":"billing@city.example","status":"PENDING_ACTIVATION",
                     "createdAt":"2026-11-13T09:00:00Z","updatedAt":"2026-11-13T09:00:00Z"}"""
                    .formatted(MERCHANT_ID))));
            MockHttpSession session = opsSession();

            mvc.perform(get("/ops/merchants").session(session))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("City Hospital")))
                    .andExpect(content().string(containsString("PENDING_ACTIVATION")));
            mvc.perform(get("/ops/merchants/{id}", MERCHANT_ID).session(session))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Activate")));
        }

        @Test
        void activationSendsTheReasonTheApiRequires() throws Exception {
            GATEWAY.stubFor(WireMock.post(urlEqualTo("/api/v1/admin/merchants/" + MERCHANT_ID + "/activate"))
                    .willReturn(okJson("""
                            {"id":"%s","legalName":"L","displayName":"D","contactEmail":"e@x.example",
                             "status":"ACTIVE","createdAt":"2026-11-13T09:00:00Z",
                             "updatedAt":"2026-11-13T09:05:00Z"}""".formatted(MERCHANT_ID))));

            mvc.perform(post("/ops/merchants/{id}/activate", MERCHANT_ID).session(opsSession()).with(csrf())
                            .param("reason", "KYC documents verified"))
                    .andExpect(redirectedUrl("/ops/merchants/" + MERCHANT_ID));

            GATEWAY.verify(postRequestedFor(urlEqualTo("/api/v1/admin/merchants/" + MERCHANT_ID + "/activate"))
                    .withRequestBody(equalToJson("{\"reason\":\"KYC documents verified\"}")));
        }

        @Test
        void anUnknownLifecycleActionIsNotForwarded() throws Exception {
            mvc.perform(post("/ops/merchants/{id}/{action}", MERCHANT_ID, "delete").session(opsSession())
                            .with(csrf()).param("reason", "because"))
                    .andExpect(redirectedUrl("/ops/merchants/" + MERCHANT_ID));
            GATEWAY.verify(0, postRequestedFor(urlEqualTo("/api/v1/admin/merchants/" + MERCHANT_ID + "/delete")));
        }

        @Test
        void notificationDeliveryIsVisibleAndRequeueable() throws Exception {
            String notificationId = "7c1d2e3f-0000-7000-8000-000000000003";
            GATEWAY.stubFor(WireMock.get(urlPathEqualTo("/api/v1/admin/notifications")).willReturn(okJson("""
                    {"content":[{"id":"%s","sourceEventId":"01933f7e-0000-7000-8000-00000000000e",
                      "eventType":"PaymentCaptured","channel":"WEBHOOK","recipientType":"MERCHANT",
                      "recipient":"https://his.example/hooks","merchantId":"%s","paymentId":"%s",
                      "sequenceNo":4,"status":"DEAD","attemptCount":12,
                      "lastError":"endpoint returned 503","createdAt":"2026-11-13T09:00:00Z",
                      "updatedAt":"2026-11-14T05:00:00Z"}],
                     "page":{"number":0,"size":20,"totalElements":1,"totalPages":1}}"""
                    .formatted(notificationId, MERCHANT_ID, PAYMENT_ID))));
            GATEWAY.stubFor(WireMock.get(urlEqualTo("/api/v1/admin/notifications/" + notificationId)).willReturn(okJson("""
                    {"notification":{"id":"%s","sourceEventId":"01933f7e-0000-7000-8000-00000000000e",
                      "eventType":"PaymentCaptured","channel":"WEBHOOK","recipientType":"MERCHANT",
                      "recipient":"https://his.example/hooks","merchantId":"%s","paymentId":"%s",
                      "sequenceNo":4,"status":"DEAD","attemptCount":12,"lastError":"endpoint returned 503",
                      "createdAt":"2026-11-13T09:00:00Z","updatedAt":"2026-11-14T05:00:00Z"},
                     "attempts":[{"attemptNo":1,"outcome":"RETRYABLE_FAILURE","responseCode":503,
                       "error":"endpoint returned 503","durationMs":102,"attemptedAt":"2026-11-13T09:00:01Z"}]}"""
                    .formatted(notificationId, MERCHANT_ID, PAYMENT_ID))));
            GATEWAY.stubFor(WireMock.post(urlEqualTo("/api/v1/admin/notifications/" + notificationId + "/requeue"))
                    .willReturn(okJson("""
                            {"id":"%s","sourceEventId":"01933f7e-0000-7000-8000-00000000000e",
                             "eventType":"PaymentCaptured","channel":"WEBHOOK","recipientType":"MERCHANT",
                             "merchantId":"%s","sequenceNo":4,"status":"PENDING","attemptCount":12,
                             "createdAt":"2026-11-13T09:00:00Z","updatedAt":"2026-11-14T06:00:00Z"}"""
                            .formatted(notificationId, MERCHANT_ID))));
            MockHttpSession session = opsSession();

            mvc.perform(get("/ops/notifications").session(session))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("DEAD")))
                    .andExpect(content().string(containsString("https://his.example/hooks")));
            mvc.perform(get("/ops/notifications/{id}", notificationId).session(session))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Requeue")))
                    .andExpect(content().string(containsString("endpoint returned 503")));
            mvc.perform(post("/ops/notifications/{id}/requeue", notificationId).session(session).with(csrf()))
                    .andExpect(redirectedUrl("/ops/notifications/" + notificationId));
        }
    }

    // ---------------------------------------------------------------- when the platform misbehaves

    @Nested
    class Resilience {

        @Test
        void aGatewayOutageDuringSignInSaysSoRatherThanRejectingTheCredentials() throws Exception {
            GATEWAY.resetAll();
            GATEWAY.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(503)));
            mvc.perform(formLogin("/login").user("username", MERCHANT_KEY).password("password", MERCHANT_SECRET))
                    .andExpect(unauthenticated())
                    .andExpect(redirectedUrl("/login?error"));
        }

        @Test
        void aFailingApiCallOnAPageLoadShowsTheProblemPage() throws Exception {
            MockHttpSession session = merchantSession();
            GATEWAY.stubFor(WireMock.get(urlPathEqualTo("/api/v1/payments")).willReturn(aResponse().withStatus(503)
                    .withHeader("Content-Type", "application/problem+json")
                    .withBody("""
                            {"status":503,"errorCode":"SERVICE_UNAVAILABLE",
                             "detail":"The service is temporarily unavailable. Retry later."}""")));

            mvc.perform(get("/payments").session(session))
                    .andExpect(status().isBadGateway())
                    .andExpect(content().string(containsString("SERVICE_UNAVAILABLE")))
                    .andExpect(content().string(containsString("temporarily unavailable")));
        }

        @Test
        void theConsoleCallsTheGatewayWithABearerTokenItNeverShows() throws Exception {
            stubPaymentsList("PENDING");
            mvc.perform(get("/payments").session(merchantSession())).andExpect(status().isOk());
            GATEWAY.verify(getRequestedFor(urlPathEqualTo("/api/v1/payments"))
                    .withHeader("Authorization", matching("Bearer eyJ.*")));
        }

        @Test
        void healthIsPublic() throws Exception {
            mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        }
    }
}
