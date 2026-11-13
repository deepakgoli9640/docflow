package com.hpg.payment.application;

import com.hpg.events.merchant.MerchantSnapshotPayload;
import com.hpg.payment.domain.CaptureMode;
import com.hpg.payment.domain.MerchantConfigReplica;
import com.hpg.payment.infrastructure.persistence.MerchantConfigReplicaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Keeps payment-service's copy of merchant settings current from {@code merchant.snapshots.v1}. Each snapshot
 * is the full state with a rising version, so applying one is idempotent and an older one is ignored.
 */
@Service
public class MerchantReplicaService {

    private static final Logger log = LoggerFactory.getLogger(MerchantReplicaService.class);

    private final MerchantConfigReplicaRepository replicas;
    private final Clock clock;

    public MerchantReplicaService(MerchantConfigReplicaRepository replicas, Clock clock) {
        this.replicas = replicas;
        this.clock = clock;
    }

    /** @return true if the snapshot was applied; false if we already hold this or a newer version */
    @Transactional
    public boolean apply(MerchantSnapshotPayload s) {
        MerchantConfigReplica replica = replicas.findById(s.merchantId()).orElseGet(() -> new MerchantConfigReplica(s.merchantId()));
        boolean applied = replica.apply(new MerchantConfigReplica.Snapshot(s.snapshotVersion(), s.displayName(), s.status(),
                s.enabledMethods(), CaptureMode.valueOf(s.captureMode()), s.minAmount(), s.maxAmountPerTransaction(),
                s.dailyLimit(), s.paymentTtlMinutes(), s.maxAttempts()), clock.instant());
        if (applied) {
            replicas.save(replica);
            log.info("Merchant replica updated merchantId={} version={} status={}", s.merchantId(), s.snapshotVersion(),
                    s.status());
        } else {
            log.debug("Ignoring stale merchant snapshot merchantId={} version={}", s.merchantId(), s.snapshotVersion());
        }
        return applied;
    }
}
