package com.hpg.merchant.application;

import com.hpg.common.correlation.CorrelationId;
import com.hpg.merchant.domain.AuditAction;
import com.hpg.merchant.domain.MerchantAuditLog;
import com.hpg.persistence.Actors;
import com.hpg.merchant.infrastructure.persistence.MerchantAuditLogRepository;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * Writes audit records in the <em>same</em> transaction as the change they describe ({@code MANDATORY}):
 * if the change rolls back, so does its audit entry, and an audit entry can never exist for a change
 * that did not happen. Details must never contain secrets.
 */
@Component
public class AuditLogger {

    private final MerchantAuditLogRepository repository;
    private final Clock clock;

    public AuditLogger(MerchantAuditLogRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID merchantId, AuditAction action, Map<String, Object> details) {
        repository.save(new MerchantAuditLog(merchantId, action, Actors.current(),
                MDC.get(CorrelationId.MDC_KEY), details, clock.instant()));
    }
}
