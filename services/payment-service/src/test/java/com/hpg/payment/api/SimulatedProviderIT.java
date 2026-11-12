package com.hpg.payment.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.common.money.Money;
import com.hpg.payment.application.PaymentRecoveryService;
import com.hpg.payment.domain.CaptureMode;
import com.hpg.payment.domain.MerchantConfigReplica;
import com.hpg.payment.infrastructure.persistence.MerchantConfigReplicaRepository;
import com.hpg.payment.support.PostgresTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The opt-in SIMULATED provider used for local demos without Razorpay keys. */
@SpringBootTest(properties = "hpg.payment.provider=simulated")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class SimulatedProviderIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    MerchantConfigReplicaRepository replicas;
    @Autowired
    PaymentRecoveryService recovery;

    UUID merchantId;

    @BeforeEach
    void seed() {
        merchantId = UUID.randomUUID();
        MerchantConfigReplica r = new MerchantConfigReplica(merchantId);
        r.apply(new MerchantConfigReplica.Snapshot(1, "Demo Hospital", "ACTIVE", Set.of("UPI"), CaptureMode.AUTO,
                Money.inr("1.00"), Money.inr("200000.00"), Money.inr("1000000.00"), 60, 3), Instant.now());
        replicas.save(r);
    }

    RequestPostProcessor merchant() {
        return jwt().jwt(j -> j.claim("mid", merchantId.toString())).authorities(new SimpleGrantedAuthority("ROLE_MERCHANT"));
    }

    String createAndGetToken() throws Exception {
        JsonNode p = json.readTree(mvc.perform(post("/api/v1/payments").with(merchant())
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantReference\":\"SIM-" + UUID.randomUUID().toString().substring(0, 6)
                                + "\",\"amount\":{\"amount\":\"1500.00\",\"currency\":\"INR\"}}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(p.get("status").asText()).isEqualTo("PENDING");
        String url = p.get("checkoutUrl").asText();
        return url.substring(url.lastIndexOf('/') + 1);
    }

    @Test
    void checkoutPageIsClearlyLabelledAsSimulated() throws Exception {
        mvc.perform(get("/checkout/{t}", createAndGetToken()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("SIMULATED PROVIDER")))
                .andExpect(content().string(containsString("Demo Hospital")));
    }

    @Test
    void simulatedSuccessCapturesThroughTheNormalCallbackPath() throws Exception {
        String token = createAndGetToken();
        mvc.perform(post("/checkout/{t}/simulate", token).param("outcome", "SUCCESS"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Payment successful")));
    }

    @Test
    void lostCallbackIsRecoveredByPolling() throws Exception {
        String token = createAndGetToken();
        mvc.perform(post("/checkout/{t}/simulate", token).param("outcome", "SUCCESS").param("deliverCallback", "false"))
                .andExpect(content().string(containsString("not completed")));
        recovery.verifyInFlight();
        mvc.perform(get("/checkout/{t}", token)).andExpect(content().string(containsString("Payment successful")));
    }
}
