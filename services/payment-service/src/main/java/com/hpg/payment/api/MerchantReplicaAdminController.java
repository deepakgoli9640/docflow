package com.hpg.payment.api;

import com.hpg.payment.api.dto.MerchantReplicaRequest;
import com.hpg.payment.application.MerchantReplicaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * <b>TEMPORARY.</b> Lets OPS copy a merchant's configuration into payment-service until it is replicated
 * automatically from {@code merchant.snapshots.v1} (not built yet). It will be removed then.
 */
@RestController
@Tag(name = "Merchant replica (OPS, temporary)", description = "Stop-gap until merchant snapshot events exist")
public class MerchantReplicaAdminController {

    private final MerchantReplicaService replicas;

    public MerchantReplicaAdminController(MerchantReplicaService replicas) {
        this.replicas = replicas;
    }

    @PutMapping("/api/v1/admin/merchant-config-replicas/{merchantId}")
    @Operation(summary = "Seed or replace a merchant's configuration replica (temporary)")
    public Map<String, Object> upsert(@PathVariable UUID merchantId, @Valid @RequestBody MerchantReplicaRequest request) {
        var replica = replicas.upsert(merchantId, request.toSnapshot());
        return Map.of("merchantId", replica.getMerchantId(), "status", replica.getStatus(),
                "snapshotVersion", replica.getSnapshotVersion());
    }
}
