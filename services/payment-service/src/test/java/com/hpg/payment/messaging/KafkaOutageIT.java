package com.hpg.payment.messaging;

import com.hpg.common.money.Money;
import com.hpg.messaging.outbox.OutboxRelay;
import com.hpg.payment.application.CreatePaymentCommand;
import com.hpg.payment.application.PaymentCreationService;
import com.hpg.payment.domain.CaptureMode;
import com.hpg.payment.domain.MerchantConfigReplica;
import com.hpg.payment.domain.PaymentStatus;
import com.hpg.payment.infrastructure.persistence.MerchantConfigReplicaRepository;
import com.hpg.payment.infrastructure.persistence.PaymentRepository;
import com.hpg.payment.support.PostgresTestContainer;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Required failure scenario 6: <b>Kafka is unavailable</b>.
 *
 * <p>There is no broker in this test — {@code spring.kafka.bootstrap-servers} points at a closed port.
 * What has to remain true is that hospitals can still take payments, that nothing is lost, and that
 * nothing is marked published. The other half of the scenario, a backlog draining in the right order once
 * Kafka returns, is covered by {@code OutboxRelayIT}, which publishes a three-event backlog against a
 * real broker and asserts the order the consumer sees.
 */
@SpringBootTest(properties = {
        "hpg.payment.provider=simulated",
        "spring.kafka.bootstrap-servers=localhost:1"})   // nothing is listening there
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class KafkaOutageIT {

    @Autowired
    PaymentCreationService creation;
    @Autowired
    PaymentRepository payments;
    @Autowired
    MerchantConfigReplicaRepository replicas;
    @Autowired
    OutboxRelay relay;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MeterRegistry meters;

    UUID merchantId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM outbox_events");
        merchantId = UUID.randomUUID();
        MerchantConfigReplica replica = new MerchantConfigReplica(merchantId);
        replica.apply(new MerchantConfigReplica.Snapshot(1, "City Hospital", "ACTIVE", Set.of("UPI"),
                CaptureMode.AUTO, Money.inr("1.00"), Money.inr("200000.00"), Money.inr("1000000.00"), 60, 3),
                Instant.now());
        replicas.save(replica);
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("hpgk_test")
                .claim("mid", merchantId.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void paymentsAreStillAcceptedAndNoEventIsLostOrFalselyMarkedPublished() {
        String reference = "BILL-" + UUID.randomUUID().toString().substring(0, 8);

        var result = creation.create(new CreatePaymentCommand(merchantId, reference, Money.inr("20000.00"), null,
                null, null, null), UUID.randomUUID().toString(), Map.of("ref", reference));

        // The hospital's request succeeded: the payment exists and the patient can be sent to checkout.
        assertThat(result.httpStatus()).isEqualTo(201);
        assertThat(result.payment().getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payments.findById(result.payment().getId())).isPresent();

        // The event is committed in the outbox, waiting.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND published_at IS NULL",
                Long.class, result.payment().getId().toString())).isEqualTo(1);

        // The relay cannot publish, reports zero, and leaves the row unpublished with the reason recorded.
        assertThat(relay.publishBatch()).isZero();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT published_at, attempts, last_error FROM outbox_events WHERE aggregate_id = ?",
                result.payment().getId().toString());
        assertThat(row.get("published_at")).isNull();
        assertThat((Integer) row.get("attempts")).isEqualTo(1);
        assertThat((String) row.get("last_error")).isNotBlank();
    }

    @Test
    void theBacklogIsVisibleToMonitoring() {
        for (int i = 0; i < 3; i++) {
            String reference = "BILL-" + UUID.randomUUID().toString().substring(0, 8);
            creation.create(new CreatePaymentCommand(merchantId, reference, Money.inr("100.00"), null, null, null,
                    null), UUID.randomUUID().toString(), Map.of("ref", reference));
        }
        relay.publishBatch();

        // This gauge is what an alert watches: a backlog that stops draining means publishing is stuck.
        assertThat(meters.get("outbox.pending.events").gauge().value()).isEqualTo(3.0);
        assertThat(meters.get("outbox.pending.oldest.age.seconds").gauge().value()).isPositive();
    }

    @Test
    void repeatedFailuresCountAttemptsWithoutLosingTheEvent() {
        String reference = "BILL-" + UUID.randomUUID().toString().substring(0, 8);
        creation.create(new CreatePaymentCommand(merchantId, reference, Money.inr("100.00"), null, null, null, null),
                UUID.randomUUID().toString(), Map.of("ref", reference));

        for (int i = 0; i < 3; i++) {
            relay.publishBatch();
        }

        assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_events", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Long.class))
                .isEqualTo(1);
    }
}
