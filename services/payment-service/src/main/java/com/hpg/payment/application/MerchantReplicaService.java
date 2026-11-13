package com.hpg.payment.application;

import com.hpg.payment.domain.MerchantConfigReplica;
import com.hpg.payment.infrastructure.persistence.MerchantConfigReplicaRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Maintains the local merchant configuration replica. It is meant to be fed by merchant.snapshots.v1;
 * until that replication exists OPS seeds it through a TEMPORARY admin endpoint.
 */
@Service
public class MerchantReplicaService {

    private final MerchantConfigReplicaRepository replicas;
    private final Clock clock;

    public MerchantReplicaService(MerchantConfigReplicaRepository replicas, Clock clock) {
        this.replicas = replicas;
        this.clock = clock;
    }

    @PreAuthorize("hasRole('OPS')")
    @Transactional
    public MerchantConfigReplica upsert(UUID merchantId, MerchantConfigReplica.Snapshot snapshot) {
        MerchantConfigReplica replica = replicas.findById(merchantId).orElseGet(() -> new MerchantConfigReplica(merchantId));
        // Manual seeding has no upstream version: each write supersedes the previous one.
        replica.apply(snapshot.withVersion(replica.getSnapshotVersion() + 1), clock.instant());
        return replicas.save(replica);
    }
}
