package com.hpg.merchant.application;

import com.hpg.events.Topics;
import com.hpg.events.merchant.MerchantEventType;
import com.hpg.events.merchant.MerchantSnapshotPayload;
import com.hpg.merchant.domain.Merchant;
import com.hpg.merchant.domain.MerchantConfiguration;
import com.hpg.merchant.domain.PaymentMethod;
import com.hpg.merchant.domain.PaymentSettings;
import com.hpg.merchant.infrastructure.persistence.MerchantConfigurationRepository;
import com.hpg.messaging.outbox.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Publishes a merchant's configuration to {@code merchant.snapshots.v1} whenever it changes.
 *
 * <p>Other services keep a local copy of what they need (payment limits, webhook URL), so no request path
 * depends on merchant-service being up. {@code MANDATORY} propagation: the snapshot is written to the
 * outbox in the same transaction as the change it describes, so the two cannot diverge.
 *
 * <p>The signing secret is never included — see {@link MerchantSnapshotPayload}.
 */
@Component
public class MerchantSnapshotPublisher {

    private static final Logger log = LoggerFactory.getLogger(MerchantSnapshotPublisher.class);

    private final MerchantConfigurationRepository configurations;
    private final OutboxWriter outbox;

    public MerchantSnapshotPublisher(MerchantConfigurationRepository configurations, OutboxWriter outbox) {
        this.configurations = configurations;
        this.outbox = outbox;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(Merchant merchant) {
        MerchantConfiguration configuration = configurations.findById(merchant.getId()).orElse(null);
        if (configuration == null) {
            // Only possible between registering a merchant and creating its configuration, inside one
            // transaction. Publishing a half-built merchant would give consumers nonsense limits.
            log.debug("No configuration yet for merchantId={}; snapshot not published", merchant.getId());
            return;
        }
        PaymentSettings settings = configuration.settings();
        long version = merchant.nextSnapshotVersion();
        MerchantSnapshotPayload payload = new MerchantSnapshotPayload(
                merchant.getId(), version, merchant.getLegalName(), merchant.getDisplayName(),
                merchant.getStatus().name(),
                settings.enabledMethods().stream().map(PaymentMethod::name).collect(Collectors.toUnmodifiableSet()),
                settings.captureMode().name(), settings.minAmount(), settings.maxAmountPerTransaction(),
                settings.dailyLimit(), settings.paymentTtlMinutes(), settings.maxAttempts(),
                settings.refundWindowDays(), settings.webhookUrl(), settings.notifyPatientEmail(),
                settings.notifyPatientSms(), merchant.getUpdatedAt());
        outbox.append(Topics.MERCHANT_SNAPSHOTS, "Merchant", merchant.getId().toString(), version, merchant.getId(),
                MerchantEventType.MerchantSnapshot.name(), MerchantEventType.CURRENT_VERSION, null, payload);
        log.debug("Merchant snapshot v{} queued for merchantId={} status={}", version, merchant.getId(),
                merchant.getStatus());
    }

    /** Convenience for call sites that only have the set of methods as strings. */
    public static Set<String> methodNames(Set<PaymentMethod> methods) {
        return methods.stream().map(PaymentMethod::name).collect(Collectors.toUnmodifiableSet());
    }
}
