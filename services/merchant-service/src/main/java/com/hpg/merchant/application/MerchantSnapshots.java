package com.hpg.merchant.application;

import com.hpg.events.Topics;
import com.hpg.events.merchant.MerchantSnapshotPayload;
import com.hpg.merchant.domain.Merchant;
import com.hpg.merchant.domain.MerchantConfiguration;
import com.hpg.merchant.domain.PaymentSettings;
import com.hpg.merchant.infrastructure.persistence.MerchantConfigurationRepository;
import com.hpg.messaging.outbox.OutboxWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.TreeSet;

/**
 * Publishes the full current state of a merchant to {@code merchant.snapshots.v1}, through the outbox, in
 * the same transaction as the change: a committed change always produces its snapshot, and a rolled-back
 * one never does. payment-service, refund-service and the gateway keep their copies from these events, so
 * none of them calls merchant-service on the request path.
 */
@Component
public class MerchantSnapshots {

    private final JdbcTemplate jdbc;
    private final MerchantConfigurationRepository configurations;
    private final OutboxWriter outbox;
    private final Clock clock;

    public MerchantSnapshots(JdbcTemplate jdbc, MerchantConfigurationRepository configurations, OutboxWriter outbox,
            Clock clock) {
        this.jdbc = jdbc;
        this.configurations = configurations;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * @param merchant the merchant as it will be committed (in-memory state, possibly not yet flushed)
     * @return the snapshot version published
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long publish(Merchant merchant) {
        MerchantConfiguration configuration = configurations.findById(merchant.getId()).orElseThrow(() ->
                new IllegalStateException("No configuration for merchant " + merchant.getId()));
        // Row-locking increment: concurrent changes of one merchant get distinct, ordered versions.
        Long version = jdbc.queryForObject(
                "UPDATE merchants SET snapshot_version = snapshot_version + 1 WHERE id = ? RETURNING snapshot_version",
                Long.class, merchant.getId());
        if (version == null) {
            throw new IllegalStateException("Merchant row " + merchant.getId() + " not found");
        }
        PaymentSettings s = configuration.settings();
        MerchantSnapshotPayload payload = new MerchantSnapshotPayload(merchant.getId(), version,
                merchant.getDisplayName(), merchant.getStatus().name(),
                s.enabledMethods().stream().map(Enum::name).collect(java.util.stream.Collectors.toCollection(TreeSet::new)),
                s.captureMode().name(), s.minAmount(), s.maxAmountPerTransaction(), s.dailyLimit(),
                s.paymentTtlMinutes(), s.maxAttempts(), s.refundWindowDays(), s.webhookUrl() != null,
                s.notifyPatientEmail(), clock.instant());
        outbox.append(Topics.MERCHANT_SNAPSHOTS, "Merchant", merchant.getId().toString(), version, merchant.getId(),
                MerchantSnapshotPayload.EVENT_TYPE, MerchantSnapshotPayload.CURRENT_VERSION, null, payload);
        return version;
    }
}
