package com.hpg.payment.application;

import com.hpg.events.merchant.MerchantSnapshotPayload;
import com.hpg.payment.domain.CaptureMode;
import com.hpg.payment.domain.MerchantConfigReplica;
import com.hpg.payment.infrastructure.persistence.MerchantConfigReplicaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Applies merchant snapshots to the local replica. The replica is read-only as far as this service is
 * concerned: merchant-service owns the data, and nothing here may change it except an incoming snapshot.
 */
@Service
public class MerchantReplicaService {

    private final MerchantConfigReplicaRepository replicas;
    private final Clock clock;

    public MerchantReplicaService(MerchantConfigReplicaRepository replicas, Clock clock) {
        this.replicas = replicas;
        this.clock = clock;
    }

    /** @return true if the snapshot was newer than the stored one and was applied */
    @Transactional
    public boolean apply(MerchantSnapshotPayload payload) {
        MerchantConfigReplica replica = replicas.findById(payload.merchantId())
                .orElseGet(() -> new MerchantConfigReplica(payload.merchantId()));
        boolean applied = replica.apply(new MerchantConfigReplica.Snapshot(
                payload.version(), payload.displayName(), payload.status(), payload.enabledMethods(),
                CaptureMode.valueOf(payload.captureMode()), payload.minAmount(), payload.maxAmountPerTransaction(),
                payload.dailyLimit(), payload.paymentTtlMinutes(), payload.maxAttempts()), clock.instant());
        if (applied) {
            replicas.save(replica);
        }
        return applied;
    }
}
