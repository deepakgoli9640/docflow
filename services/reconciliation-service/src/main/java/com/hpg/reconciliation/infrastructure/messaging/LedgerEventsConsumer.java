package com.hpg.reconciliation.infrastructure.messaging;

import com.hpg.events.Topics;
import com.hpg.events.ledger.TransactionCreatedPayload;
import com.hpg.messaging.consumer.EventMessage;
import com.hpg.messaging.consumer.EventParser;
import com.hpg.reconciliation.infrastructure.persistence.InternalTransactionStore;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/** Our side of reconciliation, from the ledger (docs/05-reconciliation.md §2). Idempotent on transaction_ref. */
@Component
public class LedgerEventsConsumer {

    public static final String GROUP = "recon-internal-txns";

    private final EventParser parser;
    private final InternalTransactionStore store;
    private final TransactionTemplate tx;
    private final Clock clock;

    public LedgerEventsConsumer(EventParser parser, InternalTransactionStore store, PlatformTransactionManager txManager,
            Clock clock) {
        this.parser = parser;
        this.store = store;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    @KafkaListener(id = GROUP, topics = Topics.LEDGER_EVENTS, groupId = GROUP)
    public void onLedgerEvent(String value) {
        EventMessage event = parser.parse(value);
        if (!TransactionCreatedPayload.EVENT_TYPE.equals(event.eventType())) {
            return;
        }
        TransactionCreatedPayload t = event.payloadAs(TransactionCreatedPayload.class);
        tx.executeWithoutResult(s -> store.insert(t.transactionRef(), t.transactionId(), t.merchantId(),
                t.transactionType(), t.paymentId(), t.refundId(), t.provider(), t.providerReference(), t.amount().amount(),
                t.amount().currency(), t.postedAt(), clock.instant()));
    }
}
