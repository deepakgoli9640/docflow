package com.hpg.transaction.application;

import com.hpg.common.id.Ids;
import com.hpg.common.money.Money;
import com.hpg.events.payment.PaymentEventPayload;
import com.hpg.events.refund.RefundEventPayload;
import com.hpg.messaging.consumer.EventMessage;
import com.hpg.messaging.consumer.PrerequisiteNotMetException;
import com.hpg.transaction.domain.PostingRules;
import com.hpg.transaction.domain.Transaction;
import com.hpg.transaction.domain.TransactionStatus;
import com.hpg.transaction.domain.TransactionType;
import com.hpg.transaction.infrastructure.persistence.LedgerStore;
import com.hpg.transaction.infrastructure.persistence.TransactionRepository;
import com.hpg.transaction.infrastructure.persistence.TransactionTimeline;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Turns payment and refund events into transaction rows, their status timeline and ledger postings —
 * all in the consumer's transaction, together with the processed-event marker.
 */
@Service
public class TransactionRecorder {

    private static final Logger log = LoggerFactory.getLogger(TransactionRecorder.class);
    private static final DateTimeFormatter REF_DATE = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final TransactionRepository transactions;
    private final TransactionTimeline timeline;
    private final LedgerStore ledger;
    private final MeterRegistry meters;
    private final Clock clock;

    public TransactionRecorder(TransactionRepository transactions, TransactionTimeline timeline, LedgerStore ledger,
            MeterRegistry meters, Clock clock) {
        this.transactions = transactions;
        this.timeline = timeline;
        this.ledger = ledger;
        this.meters = meters;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void onPaymentEvent(EventMessage event, PaymentEventPayload p) {
        TransactionStatus status = TransactionStatus.forPaymentEvent(event.eventType()).orElse(null);
        if (status == null) {
            log.debug("Ignoring payment event type {}", event.eventType());
            return;
        }
        Instant occurredAt = occurredAt(event);
        Transaction txn = upsert(transactions.findByPaymentIdAndType(p.paymentId(), TransactionType.PAYMENT).orElse(null),
                TransactionType.PAYMENT, p.merchantId(), p.paymentId(), null, p.amount(), status, event, occurredAt);
        txn.enrich(p.merchantReference(), p.provider(), p.providerPaymentId(), p.failureCode(), p.failureReason(),
                p.lateCapture());
        timeline.record(txn.getId(), event.eventId(), event.eventType(), status.name(), event.aggregateVersion(),
                occurredAt, clock.instant());

        if (status == TransactionStatus.CAPTURED) {
            if (ledger.post(event.eventId(), txn.getId(), PostingRules.PostingType.CAPTURE,
                    PostingRules.capture(p.merchantId(), p.amount()), clock.instant())) {
                meters.counter("ledger.postings", "type", "CAPTURE").increment();
            }
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void onRefundEvent(EventMessage event, RefundEventPayload r) {
        TransactionStatus status = TransactionStatus.forRefundEvent(event.eventType()).orElse(null);
        if (status == null) {
            log.debug("Ignoring refund event type {}", event.eventType());
            return;
        }
        Instant occurredAt = occurredAt(event);
        if (status == TransactionStatus.COMPLETED) {
            // Refund and capture travel on different topics: the capture may not be booked yet. Retry in place
            // (keeps this partition's order) instead of booking a refund against nothing.
            Transaction payment = transactions.findByPaymentIdAndType(r.paymentId(), TransactionType.PAYMENT)
                    .filter(t -> ledger.hasPosting(t.getId(), PostingRules.PostingType.CAPTURE))
                    .orElseThrow(() -> new PrerequisiteNotMetException("Capture of payment " + r.paymentId()
                            + " not booked yet; RefundCompleted " + event.eventId() + " will be retried"));
            log.debug("Capture for refund {} found in transaction {}", r.refundId(), payment.getTransactionRef());
        }
        Transaction txn = upsert(transactions.findByRefundId(r.refundId()).orElse(null), TransactionType.REFUND,
                r.merchantId(), r.paymentId(), r.refundId(), r.amount(), status, event, occurredAt);
        txn.enrich(r.merchantReference(), null, r.providerRefundId(), r.failureCode(), r.failureReason(), false);
        timeline.record(txn.getId(), event.eventId(), event.eventType(), status.name(), event.aggregateVersion(),
                occurredAt, clock.instant());

        if (status == TransactionStatus.COMPLETED) {
            if (ledger.post(event.eventId(), txn.getId(), PostingRules.PostingType.REFUND,
                    PostingRules.refund(r.merchantId(), r.amount()), clock.instant())) {
                meters.counter("ledger.postings", "type", "REFUND").increment();
            }
        }
    }

    private Transaction upsert(Transaction existing, TransactionType type, UUID merchantId, UUID paymentId,
            UUID refundId, Money amount, TransactionStatus status, EventMessage event, Instant occurredAt) {
        Instant now = clock.instant();
        if (existing == null) {
            String ref = "TXN-" + REF_DATE.format(occurredAt) + "-" + String.format("%06d", timeline.nextReferenceNumber());
            Transaction created = new Transaction(Ids.newId(), ref, merchantId, type, paymentId, refundId, amount, status,
                    event.aggregateVersion(), occurredAt, now);
            log.info("Transaction {} created for {} {} ({})", ref, type, type == TransactionType.PAYMENT ? paymentId
                    : refundId, status);
            // Flush now: the timeline and ledger rows written next (plain JDBC) reference this row.
            return transactions.saveAndFlush(created);
        }
        if (!existing.applyStatus(status, event.aggregateVersion(), occurredAt, now)) {
            log.info("Stale {} (version {} < {}) for {}: timeline only", event.eventType(), event.aggregateVersion(),
                    existing.getSourceVersion(), existing.getTransactionRef());
            meters.counter("transactions.stale_events").increment();
        }
        return transactions.saveAndFlush(existing);
    }

    private Instant occurredAt(EventMessage event) {
        return event.occurredAt() == null ? clock.instant() : event.occurredAt();
    }
}
