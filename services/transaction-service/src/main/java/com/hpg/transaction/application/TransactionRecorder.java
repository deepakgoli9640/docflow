package com.hpg.transaction.application;

import com.hpg.common.id.Ids;
import com.hpg.common.money.Money;
import com.hpg.events.Topics;
import com.hpg.events.ledger.TransactionCreatedPayload;
import com.hpg.messaging.outbox.OutboxWriter;
import com.hpg.events.payment.PaymentEventPayload;
import com.hpg.events.refund.RefundEventPayload;
import com.hpg.events.reconciliation.SettlementConfirmedPayload;
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
    private final OutboxWriter outbox;
    private final Clock clock;

    public TransactionRecorder(TransactionRepository transactions, TransactionTimeline timeline, LedgerStore ledger,
            MeterRegistry meters, OutboxWriter outbox, Clock clock) {
        this.transactions = transactions;
        this.timeline = timeline;
        this.ledger = ledger;
        this.meters = meters;
        this.outbox = outbox;
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
                publishCreated(txn, event.eventId());
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
                publishCreated(txn, event.eventId());
            }
        }
    }

    /**
     * Reconciliation matched a captured payment in the provider's settlement report: book the payout once per
     * payment, however many reports list it again (a later file for the same day, a re-run).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onSettlementConfirmed(EventMessage event, SettlementConfirmedPayload s) {
        Transaction payment = transactions.findByTransactionRefAndMerchantId(s.transactionRef(), s.merchantId())
                .filter(t -> t.getType() == TransactionType.PAYMENT)
                .filter(t -> ledger.hasPosting(t.getId(), PostingRules.PostingType.CAPTURE))
                .orElseThrow(() -> new PrerequisiteNotMetException("Captured payment " + s.transactionRef()
                        + " not booked; SettlementConfirmed " + event.eventId() + " will be retried"));
        if (ledger.hasPosting(payment.getId(), PostingRules.PostingType.SETTLEMENT)) {
            log.debug("Settlement of {} already booked", s.transactionRef());
            return;
        }
        Instant now = clock.instant();
        if (ledger.post(event.eventId(), payment.getId(), PostingRules.PostingType.SETTLEMENT,
                PostingRules.settlement(s.gross(), s.fee(), s.tax()), now)) {
            timeline.record(payment.getId(), event.eventId(), event.eventType(), payment.getStatus().name(),
                    payment.getSourceVersion(), s.settledAt() == null ? now : s.settledAt(), now);
            meters.counter("ledger.postings", "type", "SETTLEMENT").increment();
            log.info("Settlement booked for {} (settlement {}): gross {}, fees {}", s.transactionRef(),
                    s.settlementId(), s.gross(), s.fee().plus(s.tax()));
        }
    }

    /**
     * Money was booked: tell reconciliation (ledger.events.v1, keyed by payment so a payment's capture and
     * refunds stay ordered). Written in the same transaction as the posting, through the outbox.
     */
    private void publishCreated(Transaction txn, java.util.UUID causationEventId) {
        TransactionCreatedPayload payload = new TransactionCreatedPayload(txn.getTransactionRef(), txn.getId(),
                txn.getMerchantId(), txn.getType().name(), txn.getPaymentId(), txn.getRefundId(), txn.getProvider(),
                txn.getProviderReference(), txn.amount(), clock.instant());
        outbox.append(Topics.LEDGER_EVENTS, txn.getPaymentId().toString(), "Transaction", txn.getId().toString(), 1,
                txn.getMerchantId(), TransactionCreatedPayload.EVENT_TYPE, TransactionCreatedPayload.CURRENT_VERSION,
                causationEventId.toString(), payload);
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
