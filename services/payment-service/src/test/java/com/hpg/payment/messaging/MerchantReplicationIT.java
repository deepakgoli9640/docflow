package com.hpg.payment.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.common.id.Ids;
import com.hpg.common.money.Money;
import com.hpg.events.EventEnvelope;
import com.hpg.events.Topics;
import com.hpg.events.merchant.MerchantSnapshotPayload;
import com.hpg.payment.domain.MerchantConfigReplica;
import com.hpg.payment.infrastructure.persistence.MerchantConfigReplicaRepository;
import com.hpg.payment.support.KafkaTestContainer;
import com.hpg.payment.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** merchant.snapshots.v1 -> merchant replica -> payment creation rules. */
@SpringBootTest(properties = {"hpg.payment.provider=simulated", "spring.kafka.admin.auto-create=true",
        "spring.kafka.listener.auto-startup=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({PostgresTestContainer.class, KafkaTestContainer.class})
class MerchantReplicationIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    MerchantConfigReplicaRepository replicas;

    final UUID merchantId = UUID.randomUUID();

    void snapshot(long version, String status, String captureMode, String displayName) throws Exception {
        var payload = new MerchantSnapshotPayload(merchantId, version, displayName, status, Set.of("UPI", "CARD"),
                captureMode, Money.inr("1.00"), Money.inr("50000.00"), Money.inr("100000.00"), 30, 3, 180, false, true,
                Instant.now());
        var envelope = new EventEnvelope<>(Ids.newId(), MerchantSnapshotPayload.EVENT_TYPE, 1, Instant.now(),
                "merchant-service", "Merchant", merchantId.toString(), version, merchantId, null, null, payload);
        kafka.send(Topics.MERCHANT_SNAPSHOTS, merchantId.toString(), json.writeValueAsString(envelope)).get();
    }

    int createPayment() throws Exception {
        return mvc.perform(post("/api/v1/payments")
                        .with(jwt().jwt(j -> j.claim("mid", merchantId.toString())).authorities(new SimpleGrantedAuthority("ROLE_MERCHANT")))
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantReference\":\"REPL-" + UUID.randomUUID().toString().substring(0, 8)
                                + "\",\"amount\":{\"amount\":\"100.00\",\"currency\":\"INR\"}}"))
                .andReturn().getResponse().getStatus();
    }

    MerchantConfigReplica awaitVersion(long version) {
        await().atMost(Duration.ofSeconds(60)).until(() ->
                replicas.findById(merchantId).map(MerchantConfigReplica::getSnapshotVersion).orElse(0L) >= version);
        return replicas.findById(merchantId).orElseThrow();
    }

    @Test
    void snapshotsDriveTheReplicaAndPaymentRules() throws Exception {
        assertThat(createPayment()).isEqualTo(403); // unknown merchant: not active

        snapshot(1, "PENDING_ACTIVATION", "AUTO", "City Hospital");
        snapshot(2, "ACTIVE", "MANUAL", "City Hospital");
        MerchantConfigReplica active = awaitVersion(2);
        assertThat(active.isActive()).isTrue();
        assertThat(active.getCaptureMode().name()).isEqualTo("MANUAL");
        assertThat(active.getPaymentTtlMinutes()).isEqualTo(30);
        assertThat(createPayment()).isEqualTo(201);

        // Suspension stops new payments.
        snapshot(3, "SUSPENDED", "MANUAL", "City Hospital");
        assertThat(awaitVersion(3).getStatus()).isEqualTo("SUSPENDED");
        mvc.perform(post("/api/v1/payments")
                        .with(jwt().jwt(j -> j.claim("mid", merchantId.toString())).authorities(new SimpleGrantedAuthority("ROLE_MERCHANT")))
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantReference\":\"REPL-X\",\"amount\":{\"amount\":\"100.00\",\"currency\":\"INR\"}}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("MERCHANT_NOT_ACTIVE"));

        // A late, older snapshot (redelivery) must not undo the suspension.
        snapshot(2, "ACTIVE", "AUTO", "City Hospital");
        snapshot(4, "SUSPENDED", "MANUAL", "City Hospital (renamed)");
        MerchantConfigReplica latest = awaitVersion(4);
        assertThat(latest.getStatus()).isEqualTo("SUSPENDED");
        assertThat(latest.getDisplayName()).isEqualTo("City Hospital (renamed)");
    }
}
