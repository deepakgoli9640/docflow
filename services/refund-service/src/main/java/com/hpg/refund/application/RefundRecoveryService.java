package com.hpg.refund.application;

import com.hpg.refund.domain.Refund;
import com.hpg.refund.domain.RefundStatus;
import com.hpg.refund.infrastructure.persistence.RefundRepository;
import com.hpg.refund.infrastructure.persistence.RefundablePaymentStore;
import com.hpg.refund.provider.ProviderRefund;
import com.hpg.refund.provider.RefundProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Converges refunds whose outcome we do not know (docs/03-refund-lifecycle.md §6). The rule: never send a
 * refund again without first asking the provider whether it already has one carrying our refund id.
 */
@Service
public class RefundRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(RefundRecoveryService.class);

    private final RefundRepository refunds;
    private final RefundablePaymentStore refundables;
    private final RefundProcessor processor;
    private final RefundProvider provider;
    private final RefundProperties properties;
    private final Clock clock;

    public RefundRecoveryService(RefundRepository refunds, RefundablePaymentStore refundables,
            RefundProcessor processor, RefundProvider provider, RefundProperties properties, Clock clock) {
        this.refunds = refunds;
        this.refundables = refundables;
        this.processor = processor;
        this.provider = provider;
        this.properties = properties;
        this.clock = clock;
    }

    /** One recovery round. @return refunds examined */
    public int recover() {
        RefundProperties.Jobs jobs = properties.jobs();
        Instant now = clock.instant();
        int examined = 0;
        for (Refund r : due(RefundStatus.UNKNOWN, now.minus(jobs.unknownVerifyAfter()))) {
            examined++;
            safely(r, () -> recoverUnsent(r));
        }
        for (Refund r : due(RefundStatus.CREATED, now.minus(jobs.createdRetryAfter()))) {
            if (r.getLastAttemptAt() != null && r.getLastAttemptAt().isAfter(now.minus(jobs.createdRetryAfter()))) {
                continue;
            }
            examined++;
            safely(r, () -> recoverUnsent(r));
        }
        for (Refund r : due(RefundStatus.PROCESSING, now.minus(jobs.processingVerifyAfter()))) {
            examined++;
            safely(r, () -> {
                String paymentRef = refundables.find(r.getPaymentId()).orElseThrow().providerPaymentId();
                processor.applyEvidence(r.getId(), provider.fetchRefund(paymentRef, r.getProviderRefundId()), "poll");
            });
        }
        return examined;
    }

    /** CREATED (possibly sent before a crash) or UNKNOWN (timed out): look up first, then (re)send. */
    private void recoverUnsent(Refund r) {
        String providerPaymentId = refundables.find(r.getPaymentId()).orElseThrow().providerPaymentId();
        List<ProviderRefund> atProvider = provider.fetchRefunds(providerPaymentId);
        Optional<ProviderRefund> ours = atProvider.stream()
                .filter(p -> r.getId().toString().equals(p.ourRefundId()))
                .findFirst();
        if (ours.isPresent()) {
            log.info("Recovery found refund {} at provider as {}", r.getId(), ours.get().providerRefundId());
            processor.applyEvidence(r.getId(), ours.get(), "lookup");
            return;
        }
        if (r.getProviderAttempts() >= properties.jobs().maxProviderAttempts()) {
            log.error("Refund {} not created at provider after {} attempts; giving up", r.getId(),
                    r.getProviderAttempts());
            processor.giveUp(r.getId(), "Provider did not accept the refund after " + r.getProviderAttempts()
                    + " attempts");
            return;
        }
        if (r.getStatus() == RefundStatus.UNKNOWN) {
            processor.resetForRetry(r.getId());
        }
        processor.submit(r.getId());
    }

    private List<Refund> due(RefundStatus status, Instant updatedBefore) {
        return refunds.findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(status, updatedBefore.plusMillis(1),
                Limit.of(properties.jobs().batchSize()));
    }

    private void safely(Refund r, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            // Provider still down etc.: try again next round.
            log.warn("Recovery of refund {} ({}) failed: {}", r.getId(), r.getStatus(), e.getMessage());
        }
    }
}
