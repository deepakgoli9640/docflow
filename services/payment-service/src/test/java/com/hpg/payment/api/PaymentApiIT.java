package com.hpg.payment.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.hpg.common.money.Money;
import com.hpg.payment.application.CreatePaymentCommand;
import com.hpg.payment.application.PaymentCreationService;
import com.hpg.payment.application.PaymentRecoveryService;
import com.hpg.payment.domain.CaptureMode;
import com.hpg.payment.domain.MerchantConfigReplica;
import com.hpg.payment.infrastructure.persistence.MerchantConfigReplicaRepository;
import com.hpg.payment.support.PostgresTestContainer;
import com.hpg.payment.support.RazorpayStub;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * payment-service end to end: HTTP API, real PostgreSQL, and WireMock playing Razorpay.
 * Test names map to the required failure scenarios (docs/11-failure-scenarios.md).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class PaymentApiIT {

    static final WireMockServer RAZORPAY = new WireMockServer(wireMockConfig().dynamicPort().globalTemplating(true));

    static {
        RAZORPAY.start();
    }

    @DynamicPropertySource
    static void razorpayUrl(DynamicPropertyRegistry registry) {
        registry.add("hpg.payment.razorpay.base-url", RAZORPAY::baseUrl);
    }

    @AfterAll
    static void stop() {
        RAZORPAY.stop();
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MerchantConfigReplicaRepository replicas;
    @Autowired
    PaymentRecoveryService recovery;
    @Autowired
    PaymentCreationService creation;

    String p_auth;
    String p_bad;
    String p_c1;
    String p_c2;
    String p_cb1;
    String p_dup;
    String p_f1;
    String p_f2;
    String p_forged;
    String p_last;
    String p_late;
    String p_lost;
    String p_old;
    String p_ooo;
    String p_paid;
    String p_silent;
    String p_w1;
    RazorpayStub rzp;
    UUID merchantId;
    String orderId;

    @BeforeEach
    void setUp() {
        RAZORPAY.resetAll();
        rzp = new RazorpayStub(RAZORPAY);
        merchantId = UUID.randomUUID();
        p_auth = pid("auth");
        p_bad = pid("bad");
        p_c1 = pid("c1");
        p_c2 = pid("c2");
        p_cb1 = pid("cb1");
        p_dup = pid("dup");
        p_f1 = pid("f1");
        p_f2 = pid("f2");
        p_forged = pid("forged");
        p_last = pid("last");
        p_late = pid("late");
        p_lost = pid("lost");
        p_old = pid("old");
        p_ooo = pid("ooo");
        p_paid = pid("paid");
        p_silent = pid("silent");
        p_w1 = pid("w1");
        orderId = null;
        seedMerchant(merchantId, "ACTIVE", CaptureMode.AUTO, 3, "1000000.00");
        rzp.orderCreated(2_000_000);
    }

    // ---------------------------------------------------------------- helpers

    void seedMerchant(UUID id, String status, CaptureMode mode, int maxAttempts, String dailyLimit) {
        MerchantConfigReplica replica = replicas.findById(id).orElseGet(() -> new MerchantConfigReplica(id));
        replica.apply(new MerchantConfigReplica.Snapshot(replica.getSnapshotVersion() + 1, "City Hospital", status,
                Set.of("CARD", "UPI"), mode, Money.inr("1.00"), Money.inr("200000.00"), Money.inr(dailyLimit), 60,
                maxAttempts), Instant.now());
        replicas.save(replica);
    }

    RequestPostProcessor merchant(UUID id) {
        return jwt().jwt(j -> j.subject("hpgk_test").claim("mid", id.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_MERCHANT"));
    }

    static String body(String reference, String amount) {
        return """
                {"merchantReference":"%s","amount":{"amount":"%s","currency":"INR"},
                 "description":"Patient share","customer":{"name":"A Patient","email":"patient@example.com",
                 "phone":"+919800001234"}}""".formatted(reference, amount);
    }

    ResultActions create(String key, String body) throws Exception {
        MockHttpServletRequestBuilder req = post("/api/v1/payments").with(merchant(merchantId))
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            req.header("Idempotency-Key", key);
        }
        return mvc.perform(req);
    }

    /** Creates a payment and remembers its provider order id in {@link #orderId}. */
    JsonNode createOk(String reference) throws Exception {
        String response = create(newKey(), body(reference, "20000.00")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode payment = json.readTree(response);
        orderId = jdbc.query("SELECT provider_order_id FROM payment_orders WHERE payment_id = ?::uuid",
                rs -> rs.next() ? rs.getString(1) : null, payment.get("id").asText());
        return payment;
    }

    /** Provider payment ids are globally unique in reality; keep them unique across tests too. */
    static String pid(String prefix) {
        return "pay_" + prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    static String newKey() {
        return UUID.randomUUID().toString();
    }

    static String reference() {
        return "BILL-" + UUID.randomUUID().toString().substring(0, 8);
    }

    JsonNode getPayment(String id) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/payments/{id}", id).with(merchant(merchantId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    String statusOf(String id) throws Exception {
        return getPayment(id).get("status").asText();
    }

    String checkoutToken(JsonNode payment) {
        String url = payment.get("checkoutUrl").asText();
        return url.substring(url.lastIndexOf('/') + 1);
    }

    List<String> outboxEvents(String paymentId) {
        return jdbc.queryForList("SELECT event_type FROM outbox_events WHERE aggregate_id = ? ORDER BY id",
                String.class, paymentId);
    }

    ResultActions webhook(String body) throws Exception {
        return webhook(body, RazorpayStub.webhookSignature(body), "evt_" + UUID.randomUUID());
    }

    ResultActions webhook(String body, String signature, String eventId) throws Exception {
        return mvc.perform(post("/webhooks/razorpay/payments").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Razorpay-Signature", signature).header("X-Razorpay-Event-Id", eventId));
    }

    long now() {
        return Instant.now().getEpochSecond();
    }

    // ---------------------------------------------------------------- creation

    @Nested
    class Creation {

        @Test
        void createsPaymentProviderOrderOutboxEventAndHistory() throws Exception {
            create(newKey(), body(reference(), "20000.00"))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", startsWith("/api/v1/payments/")))
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.amount.amount").value("20000.00"))
                    .andExpect(jsonPath("$.checkoutUrl").value(startsWith("http://localhost/checkout/ck_")))
                    .andExpect(jsonPath("$.customer.email").value("p*****t@example.com"))
                    .andExpect(jsonPath("$.customer.phone").value("+91******1234"));

            RAZORPAY.verify(1, postRequestedFor(urlEqualTo("/v1/orders"))
                    .withRequestBody(equalToJson("{\"amount\":2000000,\"currency\":\"INR\"}", true, true)));
            String id = jdbc.queryForObject("SELECT id::text FROM payments WHERE merchant_id = ?", String.class, merchantId);
            assertThat(outboxEvents(id)).containsExactly("PaymentInitiated");
            assertThat(jdbc.queryForList("SELECT to_status FROM payment_status_history WHERE payment_id = ?::uuid ORDER BY id",
                    String.class, id)).containsExactly("CREATED", "PENDING");
            // Patient contact details are encrypted at rest.
            assertThat(jdbc.queryForObject("SELECT customer_email_enc FROM payments WHERE id = ?::uuid", String.class, id))
                    .startsWith("v1:").doesNotContain("patient@example.com");
        }

        @Test
        void sameIdempotencyKeyReplaysInsteadOfCreatingASecondPayment() throws Exception {
            String key = newKey();
            String request = body(reference(), "20000.00");
            String first = create(key, request).andExpect(status().isCreated())
                    .andExpect(header().doesNotExist("Idempotent-Replayed"))
                    .andReturn().getResponse().getContentAsString();
            create(key, request).andExpect(status().isCreated())
                    .andExpect(header().string("Idempotent-Replayed", "true"))
                    .andExpect(jsonPath("$.id").value(json.readTree(first).get("id").asText()));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE merchant_id = ?", Long.class, merchantId))
                    .isEqualTo(1);
            RAZORPAY.verify(1, postRequestedFor(urlEqualTo("/v1/orders")));
        }

        /** Scenario 1: patient clicks Pay twice / the hospital's client retries concurrently with the same key. */
        @Test
        void concurrentRequestsWithTheSameKeyCreateExactlyOnePayment() throws Exception {
            String key = newKey();
            CreatePaymentCommand command = new CreatePaymentCommand(merchantId, reference(), Money.inr("20000.00"),
                    null, null, null, null);
            Map<String, String> raw = Map.of("same", "request");
            Jwt token = Jwt.withTokenValue("t").header("alg", "none").subject("hpgk_test")
                    .claim("mid", merchantId.toString()).build();
            int threads = 12;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<UUID>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<UUID> task = () -> {
                    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token,
                            List.of(new SimpleGrantedAuthority("ROLE_MERCHANT"))));
                    try {
                        start.await();
                        return creation.create(command, key, raw).payment().getId();
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                };
                results.add(pool.submit(task));
            }
            start.countDown();
            List<UUID> ids = new ArrayList<>();
            for (Future<UUID> f : results) {
                ids.add(f.get());
            }
            pool.shutdown();
            assertThat(ids).hasSize(threads).allMatch(ids.get(0)::equals);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE merchant_id = ?", Long.class, merchantId))
                    .isEqualTo(1);
            RAZORPAY.verify(1, postRequestedFor(urlEqualTo("/v1/orders")));
        }

        @Test
        void sameKeyWithDifferentBodyIsRejected() throws Exception {
            String key = newKey();
            create(key, body(reference(), "20000.00")).andExpect(status().isCreated());
            create(key, body(reference(), "999.00"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REUSED"));
        }

        @Test
        void missingIdempotencyKeyIsRejected() throws Exception {
            create(null, body(reference(), "20000.00"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("MISSING_IDEMPOTENCY_KEY"));
        }

        /** Scenario 1 (business level): a second payment request for the same hospital bill. */
        @Test
        void secondLivePaymentForTheSameBillIsRejectedButAllowedAfterCancel() throws Exception {
            String ref = reference();
            JsonNode first = createOk(ref);
            create(newKey(), body(ref, "20000.00"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("DUPLICATE_PAYMENT"))
                    .andExpect(jsonPath("$.existingPaymentId").value(first.get("id").asText()));

            rzp.orderPayments(orderId);
            mvc.perform(post("/api/v1/payments/{id}/cancel", first.get("id").asText()).with(merchant(merchantId))
                    .header("Idempotency-Key", newKey())).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"));
            create(newKey(), body(ref, "20000.00")).andExpect(status().isCreated());
        }

        @Test
        void merchantLimitsAreEnforced() throws Exception {
            create(newKey(), body(reference(), "0.50")).andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errorCode").value("AMOUNT_BELOW_MINIMUM"));
            create(newKey(), body(reference(), "200000.01")).andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errorCode").value("AMOUNT_ABOVE_LIMIT"));
        }

        @Test
        void dailyLimitCountsLivePaymentsOnly() throws Exception {
            seedMerchant(merchantId, "ACTIVE", CaptureMode.AUTO, 3, "50000.00");
            create(newKey(), body(reference(), "30000.00")).andExpect(status().isCreated());
            create(newKey(), body(reference(), "25000.00"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errorCode").value("DAILY_LIMIT_EXCEEDED"))
                    .andExpect(jsonPath("$.remainingToday.amount").value("20000.00"));
        }

        @Test
        void inactiveOrUnknownMerchantCannotCreatePayments() throws Exception {
            seedMerchant(merchantId, "SUSPENDED", CaptureMode.AUTO, 3, "1000000.00");
            create(newKey(), body(reference(), "100.00")).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("MERCHANT_NOT_ACTIVE"));
            merchantId = UUID.randomUUID(); // no replica at all
            create(newKey(), body(reference(), "100.00")).andExpect(status().isForbidden());
        }

        @Test
        void invalidRequestReportsFieldViolations() throws Exception {
            create(newKey(), """
                    {"merchantReference":"bad ref!","amount":{"amount":"10.001","currency":"INR"},
                     "customer":{"email":"nope"}}""")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.violations.length()").value(3));
        }

        /** Scenario 2 (creation): provider down → payment still accepted, order created later by recovery. */
        @Test
        void providerOutageAtCreationLeavesPaymentCreatedAndRecoveryCompletesIt() throws Exception {
            RAZORPAY.resetAll();
            rzp.orderCreationFails(503);
            JsonNode payment = createOk(reference());
            assertThat(payment.get("status").asText()).isEqualTo("CREATED");
            assertThat(outboxEvents(payment.get("id").asText())).containsExactly("PaymentInitiated");

            rzp.orderCreated(2_000_000);
            recovery.retryOrderCreation();
            assertThat(statusOf(payment.get("id").asText())).isEqualTo("PENDING");
        }

        @Test
        void providerRejectionFailsThePayment() throws Exception {
            RAZORPAY.resetAll();
            rzp.orderCreationFails(400);
            JsonNode payment = createOk(reference());
            assertThat(payment.get("status").asText()).isEqualTo("FAILED");
            assertThat(payment.get("failureCode").asText()).isEqualTo("BAD_REQUEST_ERROR");
            assertThat(outboxEvents(payment.get("id").asText())).containsExactly("PaymentInitiated", "PaymentFailed");
        }
    }

    // ---------------------------------------------------------------- reads

    @Nested
    class Reads {

        @Test
        void anotherMerchantsPaymentIsNotFound() throws Exception {
            JsonNode payment = createOk(reference());
            mvc.perform(get("/api/v1/payments/{id}", payment.get("id").asText()).with(merchant(UUID.randomUUID())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("PAYMENT_NOT_FOUND"));
        }

        @Test
        void listFiltersAndRejectsUnknownSortFields() throws Exception {
            String ref = reference();
            createOk(ref);
            mvc.perform(get("/api/v1/payments").param("merchantReference", ref).param("sort", "amount,desc")
                            .with(merchant(merchantId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].merchantReference").value(ref));
            mvc.perform(get("/api/v1/payments").param("sort", "customerEmail").with(merchant(merchantId)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_SORT_FIELD"));
        }

        @Test
        void requiresAMerchantToken() throws Exception {
            mvc.perform(get("/api/v1/payments")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/v1/payments").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPS"))))
                    .andExpect(status().isForbidden());
        }
    }

    // ---------------------------------------------------------------- checkout

    @Nested
    class Checkout {

        @Test
        void checkoutPageLoadsProviderScriptWithStrictCsp() throws Exception {
            JsonNode payment = createOk(reference());
            mvc.perform(get("/checkout/{token}", checkoutToken(payment)))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Security-Policy", containsString("script-src 'nonce-")))
                    .andExpect(header().string("Cache-Control", containsString("no-store")))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                            .string(containsString(orderId)))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                            .string(containsString("checkout.razorpay.com")));
        }

        @Test
        void unknownCheckoutTokenIsA404Page() throws Exception {
            mvc.perform(get("/checkout/ck_does_not_exist")).andExpect(status().isNotFound());
        }

        @Test
        void signedCallbackIsVerifiedWithTheProviderAndCapturesThePayment() throws Exception {
            JsonNode payment = createOk(reference());
            String id = payment.get("id").asText();
            rzp.paymentLookup(p_cb1, RazorpayStub.payment(p_cb1, orderId, "captured", 2_000_000));

            mvc.perform(post("/checkout/{t}/callback", checkoutToken(payment))
                            .param("razorpay_payment_id", p_cb1).param("razorpay_order_id", orderId)
                            .param("razorpay_signature", RazorpayStub.checkoutSignature(orderId, p_cb1)))
                    .andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                            .string(containsString("Payment successful")));

            JsonNode details = getPayment(id);
            assertThat(details.get("status").asText()).isEqualTo("CAPTURED");
            assertThat(details.get("attempts").get(0).get("providerPaymentId").asText()).isEqualTo(p_cb1);
            assertThat(details.get("statusHistory").findValuesAsText("to"))
                    .containsExactly("CREATED", "PENDING", "PROCESSING", "CAPTURED");
            assertThat(outboxEvents(id)).containsExactly("PaymentInitiated", "PaymentCaptured");
            String captured = jdbc.queryForObject("SELECT payload::text FROM outbox_events WHERE aggregate_id = ? "
                    + "AND event_type = 'PaymentCaptured'", String.class, id);
            assertThat(captured).contains(p_cb1).contains("patient@example.com");
        }

        @Test
        void forgedCallbackIsRejectedAndChangesNothing() throws Exception {
            JsonNode payment = createOk(reference());
            mvc.perform(post("/checkout/{t}/callback", checkoutToken(payment))
                            .param("razorpay_payment_id", p_forged).param("razorpay_order_id", orderId)
                            .param("razorpay_signature", "0".repeat(64)))
                    .andExpect(status().isBadRequest());
            assertThat(statusOf(payment.get("id").asText())).isEqualTo("PENDING");
        }

        /** Scenario 3: provider succeeded, but we never got its answer (verification call fails). */
        @Test
        void lostProviderResponseIsRecoveredByPolling() throws Exception {
            JsonNode payment = createOk(reference());
            String id = payment.get("id").asText();
            rzp.paymentLookupFails(p_lost);
            mvc.perform(post("/checkout/{t}/callback", checkoutToken(payment))
                    .param("razorpay_payment_id", p_lost).param("razorpay_order_id", orderId)
                    .param("razorpay_signature", RazorpayStub.checkoutSignature(orderId, p_lost)));
            assertThat(statusOf(id)).isEqualTo("PROCESSING");

            rzp.orderPayments(orderId, RazorpayStub.payment(p_lost, orderId, "captured", 2_000_000));
            recovery.verifyInFlight();
            assertThat(statusOf(id)).isEqualTo("CAPTURED");
        }

        /** Scenario 3, worst case: callback AND webhook lost. The periodic poll of PENDING payments finds it. */
        @Test
        void paymentWithNoCallbackAndNoWebhookIsFoundByPolling() throws Exception {
            JsonNode payment = createOk(reference());
            rzp.orderPayments(orderId, RazorpayStub.payment(p_silent, orderId, "captured", 2_000_000));
            recovery.verifyInFlight();
            assertThat(statusOf(payment.get("id").asText())).isEqualTo("CAPTURED");
        }
    }

    // ---------------------------------------------------------------- webhooks

    @Nested
    class Webhooks {

        @Test
        void capturedWebhookCapturesThePayment() throws Exception {
            String id = createOk(reference()).get("id").asText();
            webhook(RazorpayStub.webhook("payment.captured",
                    RazorpayStub.payment(p_w1, orderId, "captured", 2_000_000), now()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("APPLIED"));
            assertThat(statusOf(id)).isEqualTo("CAPTURED");
        }

        /** Scenario 4: webhook delivered twice. */
        @Test
        void duplicateWebhookIsAcknowledgedButProcessedOnce() throws Exception {
            String id = createOk(reference()).get("id").asText();
            String body = RazorpayStub.webhook("payment.captured",
                    RazorpayStub.payment(p_dup, orderId, "captured", 2_000_000), now());
            webhook(body, RazorpayStub.webhookSignature(body), "evt_same").andExpect(jsonPath("$.outcome").value("APPLIED"));
            webhook(body, RazorpayStub.webhookSignature(body), "evt_same").andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("DUPLICATE"));
            assertThat(outboxEvents(id)).containsExactly("PaymentInitiated", "PaymentCaptured");
        }

        /** Scenario 5: webhooks out of order — "authorized" arrives after "captured". */
        @Test
        void outOfOrderWebhookDoesNotMoveThePaymentBackwards() throws Exception {
            String id = createOk(reference()).get("id").asText();
            webhook(RazorpayStub.webhook("payment.captured",
                    RazorpayStub.payment(p_ooo, orderId, "captured", 2_000_000), now()));
            webhook(RazorpayStub.webhook("payment.authorized",
                    RazorpayStub.payment(p_ooo, orderId, "authorized", 2_000_000), now()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("STALE_IGNORED"));
            assertThat(statusOf(id)).isEqualTo("CAPTURED");
        }

        @Test
        void invalidSignatureIsRejectedWith401() throws Exception {
            String id = createOk(reference()).get("id").asText();
            String body = RazorpayStub.webhook("payment.captured",
                    RazorpayStub.payment(p_bad, orderId, "captured", 2_000_000), now());
            webhook(body, "deadbeef", "evt_bad").andExpect(status().isUnauthorized());
            assertThat(statusOf(id)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_inbox WHERE provider_event_id = 'evt_bad'",
                    Long.class)).isZero();
        }

        @Test
        void webhookForAnUnknownOrderIsAcknowledged() throws Exception {
            webhook(RazorpayStub.webhook("payment.captured",
                    RazorpayStub.payment("pay_x", "order_not_ours", "captured", 100), now()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("UNKNOWN_ORDER"));
        }

        @Test
        void oldWebhookIsAcknowledgedButNotApplied() throws Exception {
            String id = createOk(reference()).get("id").asText();
            webhook(RazorpayStub.webhook("payment.captured",
                    RazorpayStub.payment(p_old, orderId, "captured", 2_000_000), now() - 3 * 86_400))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("TOO_OLD"));
            assertThat(statusOf(id)).isEqualTo("PENDING");
        }

        @Test
        void failedAttemptsFailThePaymentOnlyWhenTheBudgetIsUsedUp() throws Exception {
            seedMerchant(merchantId, "ACTIVE", CaptureMode.AUTO, 2, "1000000.00");
            String id = createOk(reference()).get("id").asText();
            webhook(RazorpayStub.webhook("payment.failed", RazorpayStub.payment(p_f1, orderId, "failed", 2_000_000), now()));
            assertThat(statusOf(id)).isEqualTo("PENDING");
            webhook(RazorpayStub.webhook("payment.failed", RazorpayStub.payment(p_f2, orderId, "failed", 2_000_000), now()));
            JsonNode p = getPayment(id);
            assertThat(p.get("status").asText()).isEqualTo("FAILED");
            assertThat(p.get("failureReason").asText()).isEqualTo("Payment declined by bank");
        }

        @Test
        void secondCaptureOnTheSameOrderIsFlaggedAsDuplicateCharge() throws Exception {
            String id = createOk(reference()).get("id").asText();
            webhook(RazorpayStub.webhook("payment.captured", RazorpayStub.payment(p_c1, orderId, "captured", 2_000_000), now()));
            webhook(RazorpayStub.webhook("payment.captured", RazorpayStub.payment(p_c2, orderId, "captured", 2_000_000), now()));
            JsonNode attempts = getPayment(id).get("attempts");
            assertThat(attempts.findValuesAsText("duplicateCapture")).containsExactly("false", "true");
        }
    }

    // ---------------------------------------------------------------- capture, cancel, expiry

    @Nested
    class Lifecycle {

        String authorizedPayment() throws Exception {
            seedMerchant(merchantId, "ACTIVE", CaptureMode.MANUAL, 3, "1000000.00");
            String id = createOk(reference()).get("id").asText();
            webhook(RazorpayStub.webhook("payment.authorized",
                    RazorpayStub.payment(p_auth, orderId, "authorized", 2_000_000), now()));
            assertThat(statusOf(id)).isEqualTo("AUTHORIZED");
            return id;
        }

        ResultActions capture(String id, String key) throws Exception {
            return mvc.perform(post("/api/v1/payments/{id}/capture", id).with(merchant(merchantId))
                    .header("Idempotency-Key", key));
        }

        @Test
        void manualCaptureAndIdempotentReplay() throws Exception {
            String id = authorizedPayment();
            rzp.capture(p_auth, (m, r) -> m.willReturn(okJson(r)),
                    RazorpayStub.payment(p_auth, orderId, "captured", 2_000_000));
            String key = newKey();
            capture(id, key).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CAPTURED"));
            capture(id, key).andExpect(status().isOk()).andExpect(header().string("Idempotent-Replayed", "true"));
            RAZORPAY.verify(1, postRequestedFor(urlEqualTo("/v1/payments/" + p_auth + "/capture")));
            assertThat(outboxEvents(id)).containsExactly("PaymentInitiated", "PaymentAuthorized", "PaymentCaptured");
        }

        /** Scenario 2: provider times out on capture → UNKNOWN, never blindly retried, then verified. */
        @Test
        void captureTimeoutBecomesUnknownAndIsResolvedByVerification() throws Exception {
            String id = authorizedPayment();
            rzp.capture(p_auth, (m, r) -> m.willReturn(okJson(r).withFixedDelay(2_000)),
                    RazorpayStub.payment(p_auth, orderId, "captured", 2_000_000));
            capture(id, newKey()).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("UNKNOWN"));
            RAZORPAY.verify(1, postRequestedFor(urlEqualTo("/v1/payments/" + p_auth + "/capture")));

            rzp.orderPayments(orderId, RazorpayStub.payment(p_auth, orderId, "captured", 2_000_000));
            recovery.verifyInFlight();
            assertThat(statusOf(id)).isEqualTo("CAPTURED");
        }

        @Test
        void captureWhileProviderIsDownLeavesThePaymentAuthorized() throws Exception {
            String id = authorizedPayment();
            rzp.capture(p_auth, (m, r) -> m.willReturn(aResponse().withStatus(503)), null);
            capture(id, newKey()).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.errorCode").value("PROVIDER_UNAVAILABLE"));
            assertThat(statusOf(id)).isEqualTo("AUTHORIZED");
        }

        @Test
        void captureOfAPendingPaymentIsAConflict() throws Exception {
            String id = createOk(reference()).get("id").asText();
            capture(id, newKey()).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_STATE_TRANSITION"));
        }

        @Test
        void cancelIsRefusedWhenTheProviderShowsThePatientAlreadyPaid() throws Exception {
            String id = createOk(reference()).get("id").asText();
            rzp.orderPayments(orderId, RazorpayStub.payment(p_paid, orderId, "captured", 2_000_000));
            mvc.perform(post("/api/v1/payments/{id}/cancel", id).with(merchant(merchantId))
                            .header("Idempotency-Key", newKey()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("PAYMENT_ALREADY_CAPTURED"));
            assertThat(statusOf(id)).isEqualTo("CAPTURED");
        }

        @Test
        void expiryAsksTheProviderFirst() throws Exception {
            String unpaid = createOk(reference()).get("id").asText();
            rzp.orderPayments(orderId);
            jdbc.update("UPDATE payments SET expires_at = now() - interval '1 minute' WHERE id = ?::uuid", unpaid);
            recovery.expirePayments();
            assertThat(statusOf(unpaid)).isEqualTo("EXPIRED");
            assertThat(outboxEvents(unpaid)).containsExactly("PaymentInitiated", "PaymentExpired");

            String paidAtTheLastSecond = createOk(reference()).get("id").asText();
            rzp.orderPayments(orderId, RazorpayStub.payment(p_last, orderId, "captured", 2_000_000));
            jdbc.update("UPDATE payments SET expires_at = now() - interval '1 minute' WHERE id = ?::uuid", paidAtTheLastSecond);
            recovery.expirePayments();
            assertThat(statusOf(paidAtTheLastSecond)).isEqualTo("CAPTURED");
        }

        @Test
        void captureAfterExpiryIsAppliedAndFlaggedAsLate() throws Exception {
            String id = createOk(reference()).get("id").asText();
            rzp.orderPayments(orderId);
            jdbc.update("UPDATE payments SET expires_at = now() - interval '1 minute' WHERE id = ?::uuid", id);
            recovery.expirePayments();
            webhook(RazorpayStub.webhook("payment.captured",
                    RazorpayStub.payment(p_late, orderId, "captured", 2_000_000), now()));
            JsonNode p = getPayment(id);
            assertThat(p.get("status").asText()).isEqualTo("CAPTURED");
            assertThat(p.get("lateCapture").asBoolean()).isTrue();
        }

        @Test
        void statusHistoryIsAppendOnly() throws Exception {
            String id = createOk(reference()).get("id").asText();
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            jdbc.update("DELETE FROM payment_status_history WHERE payment_id = ?::uuid", id))
                    .hasMessageContaining("append-only");
        }
    }

    // ---------------------------------------------------------------- disputes

    @Nested
    class Disputes {

        List<String> disputeEvents(String paymentId) {
            return jdbc.queryForList("""
                    SELECT event_type FROM outbox_events WHERE topic = 'dispute.events.v1' AND message_key = ? ORDER BY id""",
                    String.class, paymentId);
        }

        String moneyMoved(String paymentId, String eventType) {
            return jdbc.queryForObject("""
                    SELECT payload->'payload'->'moneyMoved'->>'amount' FROM outbox_events
                    WHERE topic = 'dispute.events.v1' AND message_key = ? AND event_type = ?""", String.class, paymentId, eventType);
        }

        String capturedPayment(String providerPaymentId) throws Exception {
            String id = createOk(reference()).get("id").asText();
            webhook(RazorpayStub.webhook("payment.captured",
                    RazorpayStub.payment(providerPaymentId, orderId, "captured", 2_000_000), now()))
                    .andExpect(jsonPath("$.outcome").value("APPLIED"));
            return id;
        }

        JsonNode onlyDispute() throws Exception {
            JsonNode list = json.readTree(mvc.perform(get("/api/v1/disputes").with(merchant(merchantId)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(list).hasSize(1);
            return list.get(0);
        }

        /** Chargeback with the money deducted at once; the hospital contests and wins it back. */
        @Test
        void contestedDisputeThatIsWonGivesTheMoneyBack() throws Exception {
            String pay = pid("pay_dsp");
            String disp = pid("disp");
            String paymentId = capturedPayment(pay);
            String payment = RazorpayStub.payment(pay, orderId, "captured", 2_000_000);
            long respondBy = now() + 7 * 86_400;

            webhook(RazorpayStub.disputeWebhook("payment.dispute.created", payment,
                    RazorpayStub.dispute(disp, pay, 2_000_000, 2_000_000, "open", respondBy), now()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("APPLIED"));
            JsonNode d = onlyDispute();
            assertThat(d.get("status").asText()).isEqualTo("OPEN");
            assertThat(d.at("/amountDeducted/amount").asText()).isEqualTo("20000.00");
            assertThat(d.get("reasonCode").asText()).isEqualTo("chargeback_product_not_received");
            assertThat(disputeEvents(paymentId)).containsExactly("DisputeOpened", "DisputeFundsDeducted");
            assertThat(moneyMoved(paymentId, "DisputeFundsDeducted")).isEqualTo("20000.00");

            // The same notification again (new event id): nothing new happens.
            webhook(RazorpayStub.disputeWebhook("payment.dispute.created", payment,
                    RazorpayStub.dispute(disp, pay, 2_000_000, 2_000_000, "open", respondBy), now()))
                    .andExpect(jsonPath("$.outcome").value("NO_CHANGE"));

            String id = d.get("id").asText();
            RAZORPAY.stubFor(com.github.tomakehurst.wiremock.client.WireMock.patch(
                            com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/v1/disputes/" + disp + "/contest"))
                    .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath("$.summary"))
                    .willReturn(com.github.tomakehurst.wiremock.client.WireMock.okJson(
                            RazorpayStub.dispute(disp, pay, 2_000_000, 2_000_000, "under_review", respondBy))));
            mvc.perform(post("/api/v1/disputes/{id}/contest", id).with(merchant(merchantId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"summary\":\"Consultation on 12 Nov, signed consent and invoice INV-77 attached\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UNDER_REVIEW"))
                    .andExpect(jsonPath("$.merchantResponse").value("CONTESTED"));
            mvc.perform(post("/api/v1/disputes/{id}/accept", id).with(merchant(merchantId)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("DISPUTE_NOT_OPEN"));

            webhook(RazorpayStub.disputeWebhook("payment.dispute.won", payment,
                    RazorpayStub.dispute(disp, pay, 2_000_000, 0, "won", respondBy), now()))
                    .andExpect(jsonPath("$.outcome").value("APPLIED"));
            // A late "under review" never reopens a decided dispute.
            webhook(RazorpayStub.disputeWebhook("payment.dispute.under_review", payment,
                    RazorpayStub.dispute(disp, pay, 2_000_000, 2_000_000, "under_review", respondBy), now()))
                    .andExpect(jsonPath("$.outcome").value("NO_CHANGE"));

            JsonNode won = onlyDispute();
            assertThat(won.get("status").asText()).isEqualTo("WON");
            assertThat(won.at("/amountDeducted/amount").asText()).isEqualTo("0.00");
            assertThat(won.get("resolvedAt").isNull()).isFalse();
            assertThat(disputeEvents(paymentId)).containsExactly("DisputeOpened", "DisputeFundsDeducted",
                    "DisputeFundsReinstated", "DisputeResolved");
            assertThat(moneyMoved(paymentId, "DisputeFundsReinstated")).isEqualTo("20000.00");
            assertThat(statusOf(paymentId)).isEqualTo("CAPTURED");          // the payment itself is untouched

            // Another hospital cannot see it.
            mvc.perform(get("/api/v1/disputes/{id}", id).with(merchant(UUID.randomUUID())))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("DISPUTE_NOT_FOUND"));
        }

        /** Nothing deducted yet; the hospital accepts; losing books the full chargeback. */
        @Test
        void acceptedDisputeIsLostAndChargedBack() throws Exception {
            String pay = pid("pay_acc");
            String disp = pid("disp");
            String paymentId = capturedPayment(pay);
            long respondBy = now() + 5 * 86_400;
            webhook(RazorpayStub.disputeWebhook("payment.dispute.created", RazorpayStub.payment(pay, orderId, "captured", 2_000_000),
                    RazorpayStub.dispute(disp, pay, 500_000, 0, "open", respondBy), now()))
                    .andExpect(jsonPath("$.outcome").value("APPLIED"));
            assertThat(disputeEvents(paymentId)).containsExactly("DisputeOpened");

            RAZORPAY.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                            com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/v1/disputes/" + disp + "/accept"))
                    .willReturn(com.github.tomakehurst.wiremock.client.WireMock.okJson(
                            RazorpayStub.dispute(disp, pay, 500_000, 0, "lost", respondBy))));
            mvc.perform(post("/api/v1/disputes/{id}/accept", onlyDispute().get("id").asText()).with(merchant(merchantId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("LOST"))
                    .andExpect(jsonPath("$.merchantResponse").value("ACCEPTED"))
                    .andExpect(jsonPath("$.amountDeducted.amount").value("5000.00"));
            assertThat(disputeEvents(paymentId)).containsExactly("DisputeOpened", "DisputeFundsDeducted", "DisputeResolved");
            assertThat(moneyMoved(paymentId, "DisputeFundsDeducted")).isEqualTo("5000.00");
        }

        @Test
        void disputeForAnUnknownPaymentIsAcknowledged() throws Exception {
            webhook(RazorpayStub.disputeWebhook("payment.dispute.created", RazorpayStub.payment("pay_nope", "order_x", "captured", 100),
                    RazorpayStub.dispute(pid("disp"), "pay_nope", 100, 0, "open", now() + 86_400), now()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("UNKNOWN_ORDER"));
        }
    }
}
