package com.hpg.refund.application;

import com.hpg.refund.domain.Refund;
import com.hpg.refund.infrastructure.persistence.RefundRepository;
import com.hpg.refund.infrastructure.persistence.WebhookInbox;
import com.hpg.refund.provider.ProviderRefund;
import com.hpg.refund.provider.RefundProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/** Provider refund webhooks: verified, deduplicated, then applied like any other provider evidence. */
@Service
public class RefundWebhookService {

    private static final Logger log = LoggerFactory.getLogger(RefundWebhookService.class);

    public enum Outcome { INVALID_SIGNATURE, DUPLICATE, IGNORED, APPLIED }

    private final RefundProvider provider;
    private final WebhookInbox inbox;
    private final RefundRepository refunds;
    private final RefundProcessor processor;
    private final Clock clock;

    public RefundWebhookService(RefundProvider provider, WebhookInbox inbox, RefundRepository refunds,
            RefundProcessor processor, Clock clock) {
        this.provider = provider;
        this.inbox = inbox;
        this.refunds = refunds;
        this.processor = processor;
        this.clock = clock;
    }

    public Outcome handle(String eventId, String signature, byte[] body) {
        if (!provider.verifyWebhookSignature(body, signature)) {
            log.warn("Rejected refund webhook with invalid signature eventId={}", eventId);
            return Outcome.INVALID_SIGNATURE;
        }
        Optional<ProviderRefund> parsed = provider.parseWebhook(body);
        String eventType = parsed.map(p -> "refund." + p.status().name().toLowerCase(java.util.Locale.ROOT))
                .orElse("other");
        if (!inbox.record(provider.name(), eventId, eventType, body, clock.instant())) {
            return Outcome.DUPLICATE;
        }
        Optional<Refund> refund = parsed.flatMap(this::findRefund);
        if (refund.isEmpty()) {
            inbox.outcome(provider.name(), eventId, "IGNORED");
            return Outcome.IGNORED;
        }
        processor.applyEvidence(refund.get().getId(), parsed.get(), eventId);
        inbox.outcome(provider.name(), eventId, "APPLIED");
        return Outcome.APPLIED;
    }

    private Optional<Refund> findRefund(ProviderRefund evidence) {
        if (evidence.ourRefundId() != null) {
            try {
                Optional<Refund> byOurId = refunds.findById(UUID.fromString(evidence.ourRefundId()));
                if (byOurId.isPresent()) {
                    return byOurId;
                }
            } catch (IllegalArgumentException notOurs) {
                log.debug("Webhook refund carries a non-HPG receipt {}", evidence.ourRefundId());
            }
        }
        return refunds.findByProviderAndProviderRefundId(provider.name(), evidence.providerRefundId());
    }
}
