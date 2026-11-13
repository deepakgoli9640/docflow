package com.hpg.refund.application;

import com.hpg.common.correlation.CorrelationId;
import com.hpg.common.error.BusinessException;
import com.hpg.common.money.Money;
import com.hpg.refund.domain.InitiatedBy;
import com.hpg.refund.domain.Refund;
import com.hpg.refund.domain.RefundErrorCode;
import com.hpg.refund.domain.RefundablePayment;
import com.hpg.refund.infrastructure.persistence.MerchantReplicaStore;
import com.hpg.refund.infrastructure.persistence.RefundRepository;
import com.hpg.refund.infrastructure.persistence.RefundablePaymentStore;
import com.hpg.refund.provider.RefundProvider;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * Creating a refund (docs/03-refund-lifecycle.md §7):
 * <ol>
 *   <li>One transaction: idempotency key, validation, atomic reservation, refund row (CREATED), history,
 *       outbox {@code RefundInitiated}.</li>
 *   <li>After commit: the provider call ({@link RefundProcessor#submit}). Never inside the transaction —
 *       a slow provider must not hold database locks, and a rollback cannot undo a refund at the provider.</li>
 * </ol>
 */
@Service
public class RefundService {

    static final String OPERATION = "CREATE_REFUND";

    private final RefundablePaymentStore refundables;
    private final MerchantReplicaStore merchants;
    private final RefundRepository refunds;
    private final RefundTransitions transitions;
    private final IdempotencyService idempotency;
    private final RefundProcessor processor;
    private final RefundProvider provider;
    private final RefundProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    public RefundService(RefundablePaymentStore refundables, MerchantReplicaStore merchants, RefundRepository refunds,
            RefundTransitions transitions,
            IdempotencyService idempotency, RefundProcessor processor, RefundProvider provider,
            RefundProperties properties, PlatformTransactionManager txManager, Clock clock) {
        this.refundables = refundables;
        this.merchants = merchants;
        this.refunds = refunds;
        this.transitions = transitions;
        this.idempotency = idempotency;
        this.processor = processor;
        this.provider = provider;
        this.properties = properties;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    public RefundResult create(CreateRefundCommand command, String idempotencyKey, Object requestForHash) {
        idempotency.requireValidKey(idempotencyKey);
        String hash = idempotency.hash(requestForHash);
        var replay = idempotency.findReplay(command.merchantId(), OPERATION, idempotencyKey, hash);
        if (replay.isPresent()) {
            Refund existing = refunds.findById(replay.get().resourceId()).orElseThrow();
            return new RefundResult(existing, replay.get().responseStatus(), true);
        }

        Refund refund = tx.execute(status -> reserveAndCreate(command, idempotencyKey, hash));
        Refund submitted = processor.submit(refund.getId());
        return new RefundResult(submitted, 201, false);
    }

    private Refund reserveAndCreate(CreateRefundCommand c, String key, String hash) {
        Instant now = clock.instant();
        // No replica yet = the snapshot is still on its way; the merchant got a token, so it was active then.
        var merchant = merchants.find(c.merchantId());
        if (merchant.isPresent() && !merchant.get().isActive()) {
            throw new BusinessException(RefundErrorCode.MERCHANT_SUSPENDED,
                    "Refunds are not available while the merchant is " + merchant.get().status().toLowerCase() + ".");
        }
        RefundablePayment payment = refundables.find(c.paymentId(), c.merchantId()).orElseThrow(() ->
                // We cannot tell "never captured" from "capture event not consumed yet": answer retryably.
                new BusinessException(RefundErrorCode.PAYMENT_NOT_YET_REFUNDABLE,
                        "Payment " + c.paymentId() + " is not refundable yet. Refund only payments you have seen "
                                + "as CAPTURED; if it was just captured, retry in a few seconds.",
                        Map.of("retryAfterSeconds", 2)));
        if (!payment.currency().equals(c.amount().currency())) {
            throw new BusinessException(RefundErrorCode.CURRENCY_MISMATCH, "The payment was made in "
                    + payment.currency() + "; refunds must use the same currency.");
        }
        if (!payment.provider().equals(provider.name())) {
            throw new BusinessException(RefundErrorCode.PROVIDER_MISMATCH, "The payment was processed by "
                    + payment.provider() + ", which this deployment is not configured to refund.");
        }
        java.time.Duration window = merchant.map(m -> java.time.Duration.ofDays(m.refundWindowDays()))
                .orElse(properties.refundWindow());
        if (payment.capturedAt().plus(window).isBefore(now)) {
            throw new BusinessException(RefundErrorCode.REFUND_WINDOW_EXPIRED, "Refunds are accepted for "
                    + window.toDays() + " days after capture.");
        }
        if (!refundables.reserve(c.paymentId(), c.merchantId(), c.amount(), now)) {
            Money remaining = refundables.find(c.paymentId()).orElseThrow().remainingRefundable();
            throw new BusinessException(RefundErrorCode.REFUND_EXCEEDS_CAPTURED_AMOUNT, "Requested "
                    + c.amount() + " but only " + remaining + " remains refundable.",
                    Map.of("remainingRefundable", remaining));
        }
        Refund refund = refunds.save(new Refund(c.paymentId(), c.merchantId(), c.amount(), c.reason(),
                InitiatedBy.MERCHANT, provider.name(), MDC.get(CorrelationId.MDC_KEY)));
        transitions.created(refund, payment, key);
        if (!idempotency.record(c.merchantId(), OPERATION, key, hash, refund.getId(), 201)) {
            // A concurrent request with the same key won the race; roll back our reservation and refund.
            throw new BusinessException(RefundErrorCode.REQUEST_IN_PROGRESS,
                    "A request with this Idempotency-Key is being processed. Retry to get its result.");
        }
        return refund;
    }
}
