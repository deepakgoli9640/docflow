package com.hpg.payment.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.hpg.common.money.Money;
import com.hpg.payment.domain.CaptureMode;
import com.hpg.payment.domain.MerchantConfigReplica;
import com.hpg.payment.infrastructure.persistence.MerchantConfigReplicaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Failure scenario 8 (docs/11-failure-scenarios.md): the database stops. Requests fail fast (Hikari connection
 * timeout, 3 s) with a 503 problem detail and Retry-After instead of hanging or returning 500, nothing is
 * half-written, and the service recovers on its own when the database is back.
 *
 * <p>Uses its own PostgreSQL container on a fixed host port: a stopped container gets a new random port when it
 * starts again, and the application would never find it.
 */
@SpringBootTest(properties = {"hpg.payment.provider=simulated", "spring.datasource.hikari.connection-timeout=3000"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(DatabaseDownIT.FixedPortPostgres.class)
class DatabaseDownIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedPortPostgres {
        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() throws Exception {
            int port;
            try (ServerSocket s = new ServerSocket(0)) {
                port = s.getLocalPort();
            }
            int hostPort = port;
            PostgreSQLContainer<?> c = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("payment_db");
            c.withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindPort(hostPort), new ExposedPort(5432))));
            return c;
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    MerchantConfigReplicaRepository replicas;
    @Autowired
    PostgreSQLContainer<?> postgres;

    @Test
    void databaseOutageIsA503WithRetryAfterAndTheServiceRecoversByItself() throws Exception {
        UUID merchantId = UUID.randomUUID();
        MerchantConfigReplica r = new MerchantConfigReplica(merchantId);
        r.apply(new MerchantConfigReplica.Snapshot(1, "DB Hospital", "ACTIVE", Set.of("UPI"), CaptureMode.AUTO,
                Money.inr("1.00"), Money.inr("200000.00"), Money.inr("1000000.00"), 60, 3), Instant.now());
        replicas.save(r);
        JwtRequestPostProcessor merchant = jwt().jwt(j -> j.claim("mid", merchantId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_MERCHANT"));
        String paymentId = json.readTree(mvc.perform(post("/api/v1/payments").with(merchant)
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantReference\":\"DB-1\",\"amount\":{\"amount\":\"100.00\",\"currency\":\"INR\"}}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();

        var docker = DockerClientFactory.instance().client();
        docker.stopContainerCmd(postgres.getContainerId()).exec();
        String retryKey = UUID.randomUUID().toString();
        try {
            long start = System.nanoTime();
            mvc.perform(get("/api/v1/payments/{id}", paymentId).with(merchant))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "5"))
                    .andExpect(jsonPath("$.errorCode").value("SERVICE_UNAVAILABLE"));
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(15));

            mvc.perform(post("/api/v1/payments").with(merchant).header("Idempotency-Key", retryKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"merchantReference\":\"DB-2\",\"amount\":{\"amount\":\"200.00\",\"currency\":\"INR\"}}"))
                    .andExpect(status().isServiceUnavailable());
        } finally {
            docker.startContainerCmd(postgres.getContainerId()).exec();
        }

        // Back without a restart of the service: the pool reconnects.
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() ->
                mvc.perform(get("/api/v1/payments/{id}", paymentId).with(merchant)).andExpect(status().isOk()));
        // The client retries with the same Idempotency-Key, as the 503 told it to: exactly one payment is created.
        JsonNode created = json.readTree(mvc.perform(post("/api/v1/payments").with(merchant)
                        .header("Idempotency-Key", retryKey).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantReference\":\"DB-2\",\"amount\":{\"amount\":\"200.00\",\"currency\":\"INR\"}}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(created.get("merchantReference").asText()).isEqualTo("DB-2");
        mvc.perform(get("/api/v1/payments").with(merchant)).andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));
    }
}
