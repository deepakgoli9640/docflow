package com.hpg.notification.application;

import com.hpg.events.merchant.MerchantSnapshotPayload;
import com.hpg.notification.domain.MerchantNotificationReplica;
import com.hpg.notification.infrastructure.client.MerchantServiceClient;
import com.hpg.notification.infrastructure.persistence.MerchantNotificationReplicaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Applies merchant snapshots to the local replica. merchant-service owns this data; we only mirror it. */
@Service
public class MerchantReplicaService {

    private final MerchantNotificationReplicaRepository replicas;
    private final MerchantServiceClient merchantClient;
    private final Clock clock;

    public MerchantReplicaService(MerchantNotificationReplicaRepository replicas,
            MerchantServiceClient merchantClient, Clock clock) {
        this.replicas = replicas;
        this.merchantClient = merchantClient;
        this.clock = clock;
    }

    /** @return true if the snapshot was newer than the stored one and was applied */
    @Transactional
    public boolean apply(MerchantSnapshotPayload payload) {
        MerchantNotificationReplica replica = replicas.findById(payload.merchantId())
                .orElseGet(() -> new MerchantNotificationReplica(payload.merchantId()));
        boolean urlChanged = !Objects.equals(replica.getWebhookUrl(), payload.webhookUrl());
        boolean applied = replica.apply(payload.version(), payload.displayName(), payload.status(),
                payload.webhookUrl(), payload.notifyPatientEmail(), payload.notifyPatientSms(), clock.instant());
        if (applied) {
            replicas.save(replica);
            if (urlChanged) {
                // A merchant that changed its webhook URL has very likely rotated its secret too; drop the
                // cached one so the next signature is made with the current secret rather than a stale one.
                merchantClient.evict(payload.merchantId());
            }
        }
        return applied;
    }

    public Optional<MerchantNotificationReplica> find(UUID merchantId) {
        return replicas.findById(merchantId);
    }
}
