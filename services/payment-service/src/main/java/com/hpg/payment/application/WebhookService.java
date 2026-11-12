package com.hpg.payment.application;

import com.hpg.common.error.BusinessException;
import com.hpg.common.error.CommonErrorCode;
import com.hpg.payment.domain.Payment;
import com.hpg.payment.domain.PaymentOrder;
import com.hpg.payment.domain.TransitionSource;
import com.hpg.payment.infrastructure.persistence.WebhookInbox;
import com.hpg.payment.provider.PaymentProvider;
import com.hpg.payment.provider.ProviderWebhookEvent;
import com.hpg.payment.provider.resilience.PaymentProviders;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Provider webhooks (docs/09-security.md §4):
 * verify the signature over the raw bytes → parse → deduplicate by event id (inbox) → apply → record the
 * outcome, all in one transaction. Any failure rolls everything back and returns 5xx so the provider
 * redelivers; a redelivery of an already-processed event is acknowledged without reprocessing.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    public enum Outcome { APPLIED, NO_CHANGE, STALE_IGNORED, UNKNOWN_ORDER, DUPLICATE, TOO_OLD, NOT_A_PAYMENT_EVENT }

    private final PaymentProviders providers;
    private final WebhookInbox inbox;
    private final PaymentEvidenceService evidence;
    private final PaymentLookup lookup;
    private final PaymentProperties properties;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;
    private final Clock clock;

    public WebhookService(PaymentProviders providers, WebhookInbox inbox, PaymentEvidenceService evidence,
            PaymentLookup lookup, PaymentProperties properties, TransactionTemplate tx, MeterRegistry meters,
            Clock clock) {
        this.providers = providers;
        this.inbox = inbox;
        this.evidence = evidence;
        this.lookup = lookup;
        this.properties = properties;
        this.tx = tx;
        this.meters = meters;
        this.clock = clock;
    }

    /** Thrown for a bad signature: answered with 401 and never processed. */
    public static class InvalidSignatureException extends RuntimeException {
        public InvalidSignatureException() {
            super("Invalid webhook signature");
        }
    }

    public Outcome handle(byte[] rawBody, String signature, String headerEventId) {
        PaymentProvider provider = providers.live();
        if (signature == null || !provider.verifyWebhookSignature(rawBody, signature)) {
            meters.counter("webhooks.signature_failures", "provider", provider.name()).increment();
            log.warn("Rejected webhook with invalid signature (eventId={})", headerEventId);
            throw new InvalidSignatureException();
        }
        Optional<ProviderWebhookEvent> parsed;
        try {
            parsed = provider.parseWebhook(eventIdOrHash(headerEventId, rawBody), rawBody);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonErrorCode.MALFORMED_REQUEST, "Webhook body is not valid JSON.");
        }
        String eventId = eventIdOrHash(headerEventId, rawBody);
        String eventType = parsed.map(ProviderWebhookEvent::eventType).orElse("unknown");
        String body = new String(rawBody, StandardCharsets.UTF_8);

        Outcome outcome = OptimisticRetry.run("webhook", () -> tx.execute(status -> {
            Instant now = clock.instant();
            Long inboxId = inbox.receive(provider.name(), eventId, eventType, body, now);
            if (inboxId == null) {
                return Outcome.DUPLICATE;
            }
            Outcome result = process(provider, parsed, now);
            inbox.complete(inboxId, result.name(), null, now);
            return result;
        }));
        meters.counter("webhooks.received", "provider", provider.name(), "type", eventType, "outcome", outcome.name())
                .increment();
        log.info("Webhook {} type={} outcome={}", eventId, eventType, outcome);
        return outcome;
    }

    private Outcome process(PaymentProvider provider, Optional<ProviderWebhookEvent> parsed, Instant now) {
        if (parsed.isEmpty()) {
            return Outcome.NOT_A_PAYMENT_EVENT;
        }
        ProviderWebhookEvent event = parsed.get();
        if (event.createdAt() != null && event.createdAt().isBefore(now.minus(properties.webhookMaxAge()))) {
            // Replay protection: old events are acknowledged (so they are not redelivered) but not applied.
            return Outcome.TOO_OLD;
        }
        Optional<PaymentOrder> order = evidence.orderFor(provider.name(), event.payment());
        if (order.isEmpty()) {
            return Outcome.UNKNOWN_ORDER;
        }
        Payment payment = lookup.require(order.get().getPaymentId());
        return switch (evidence.apply(payment, order.get(), event.payment(), TransitionSource.PROVIDER_WEBHOOK,
                event.eventId())) {
            case APPLIED -> Outcome.APPLIED;
            case STALE_IGNORED -> Outcome.STALE_IGNORED;
            case UNKNOWN_ORDER -> Outcome.UNKNOWN_ORDER;
            case NO_CHANGE -> Outcome.NO_CHANGE;
        };
    }

    /** Providers normally send an event id header; if not, the body hash still deduplicates exact repeats. */
    private static String eventIdOrHash(String headerEventId, byte[] body) {
        if (headerEventId != null && !headerEventId.isBlank()) {
            return headerEventId.length() > 100 ? headerEventId.substring(0, 100) : headerEventId;
        }
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)).substring(0, 64);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
