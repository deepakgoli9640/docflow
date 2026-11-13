package com.hpg.notification.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpg.common.money.Money;
import com.hpg.events.merchant.MerchantSnapshotPayload;
import com.hpg.notification.application.MerchantReplicaService;
import com.hpg.notification.application.NotificationIntakeService;
import com.hpg.notification.domain.Notification;
import com.hpg.notification.support.PostgresTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The operations API: who may see delivery history, and what it shows. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class NotificationAdminApiIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    NotificationIntakeService intake;
    @Autowired
    MerchantReplicaService replicas;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate jdbc;

    UUID merchantId;
    Notification notification;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE notification_attempts, notifications, outbox_events");
        merchantId = UUID.randomUUID();
        replicas.apply(new MerchantSnapshotPayload(merchantId, 1, "City Hospital Pvt Ltd", "City Hospital", "ACTIVE",
                Set.of("UPI"), "AUTO", Money.inr("1.00"), Money.inr("200000.00"), Money.inr("1000000.00"), 60, 3, 180,
                "https://his.example/hooks", true, false, Instant.now()));
        var payload = json.createObjectNode();
        payload.put("paymentId", UUID.randomUUID().toString());
        payload.put("merchantReference", "BILL-ADMIN-1");
        payload.put("status", "CAPTURED");
        payload.putObject("amount").put("amount", "20000.00").put("currency", "INR");
        payload.putObject("customer").put("email", "patient@example.com");
        notification = tx.execute(s -> intake.intake(UUID.randomUUID(), "PaymentCaptured", merchantId, 3, payload))
                .stream().filter(n -> n.getChannel() == com.hpg.notification.domain.Channel.EMAIL).findFirst()
                .orElseThrow();
    }

    private RequestPostProcessor ops() {
        return jwt().jwt(j -> j.subject("ops-console")).authorities(new SimpleGrantedAuthority("ROLE_OPS"));
    }

    private RequestPostProcessor merchant() {
        return jwt().jwt(j -> j.claim("mid", merchantId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_MERCHANT"));
    }

    @Test
    void operatorsCanListAndInspectDeliveries() throws Exception {
        mvc.perform(get("/api/v1/admin/notifications").param("status", "PENDING").with(ops()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].eventType").value("PaymentCaptured"))
                .andExpect(jsonPath("$.page.totalElements").value(2));

        mvc.perform(get("/api/v1/admin/notifications/{id}", notification.getId()).with(ops()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notification.channel").value("EMAIL"))
                // Support staff see which address was used, not the address itself.
                .andExpect(jsonPath("$.notification.recipient").value("p*****t@example.com"))
                .andExpect(jsonPath("$.attempts").isEmpty());
    }

    @Test
    void merchantsHaveNoAccessToTheOperationsApi() throws Exception {
        mvc.perform(get("/api/v1/admin/notifications").with(merchant()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/admin/notifications"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
    }

    @Test
    void onlyADeadNotificationCanBeRequeued() throws Exception {
        mvc.perform(post("/api/v1/admin/notifications/{id}/requeue", notification.getId()).with(ops()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("NOTIFICATION_NOT_REQUEUEABLE"))
                .andExpect(jsonPath("$.currentStatus").value("PENDING"));

        jdbc.update("UPDATE notifications SET status = 'DEAD', next_attempt_at = NULL WHERE id = ?",
                notification.getId());
        mvc.perform(post("/api/v1/admin/notifications/{id}/requeue", notification.getId()).with(ops()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void unknownNotificationIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/admin/notifications/{id}", UUID.randomUUID()).with(ops()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOTIFICATION_NOT_FOUND"));
    }

    @Test
    void unknownSortFieldIsRejected() throws Exception {
        mvc.perform(get("/api/v1/admin/notifications").param("sort", "recipient").with(ops()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_SORT_FIELD"));
    }
}
