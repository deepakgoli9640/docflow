package com.hpg.payment.application;

import com.hpg.common.correlation.CorrelationId;
import com.hpg.common.error.BusinessException;
import com.hpg.common.money.Money;
import com.hpg.payment.domain.MerchantConfigReplica;
import com.hpg.payment.domain.Payment;
import com.hpg.payment.domain.PaymentErrorCode;
import com.hpg.payment.infrastructure.persistence.MerchantConfigReplicaRepository;
import com.hpg.payment.infrastructure.persistence.MerchantLocks;
import com.hpg.payment.infrastructure.persistence.PaymentRepository;
import com.hpg.payment.provider.resilience.PaymentProviders;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;

/**
 * Payment initiation (docs/02-payment-lifecycle.md §4).
 *
 * <ol>
 *   <li>Transaction 1: lock the merchant's creation lock, check Idempotency-Key (replay?), validate against
 *       the merchant configuration (status, limits, daily total), check for a live payment for the same
 *       bill, then insert the payment, its history row, its PaymentInitiated outbox event and the
 *       Idempotency-Key record — all or nothing.</li>
 *   <li>Outside any transaction: create the provider order (failure leaves the payment CREATED).</li>
 * </ol>
 */
@Service
public class PaymentCreationService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PaymentCreationService.class);

    static final String OPERATION = "CREATE_PAYMENT";

    private final PaymentRepository payments;
    private final MerchantConfigReplicaRepository merchants;
    private final MerchantLocks locks;
    private final IdempotencyService idempotency;
    private final PaymentTransitions transitions;
    private final ProviderOrderService providerOrders;
    private final PaymentProviders providers;
    private final PaymentLookup lookup;
    private final PaymentProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PaymentCreationService(PaymentRepository payments, MerchantConfigReplicaRepository merchants,
            MerchantLocks locks, IdempotencyService idempotency, PaymentTransitions transitions,
            ProviderOrderService providerOrders, PaymentProviders providers, PaymentLookup lookup,
            PaymentProperties properties, TransactionTemplate tx, Clock clock) {
        this.payments = payments;
        this.merchants = merchants;
        this.locks = locks;
        this.idempotency = idempotency;
        this.transitions = transitions;
        this.providerOrders = providerOrders;
        this.providers = providers;
        this.lookup = lookup;
        this.properties = properties;
        this.tx = tx;
        this.clock = clock;
    }

    @PreAuthorize("hasRole('MERCHANT')")
    public PaymentResult create(CreatePaymentCommand command, String idempotencyKey, Object rawRequest) {
        idempotency.requireValidKey(idempotencyKey);
        String requestHash = idempotency.hash(rawRequest);
        validateReturnUrl(command.returnUrl());

        PaymentResult result;
        try {
            result = tx.execute(status -> createInTransaction(command, idempotencyKey, requestHash));
        } catch (DataIntegrityViolationException e) {
            // Backstop for the partial unique index on (merchant_id, merchant_reference). The advisory lock
            // makes this path practically unreachable, but correctness must not rely on it alone.
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("ux_payments_active_reference")) {
                throw duplicate(command, null);
            }
            throw e;
        }
        if (result == null || result.replayed()) {
            return result;
        }
        try {
            providerOrders.ensureOrder(result.payment().getId(), providers.live());
        } catch (RuntimeException e) {
            // The payment is committed: answer 201 (status CREATED) and let recovery finish the order.
            // Turning this into an error would make the hospital believe nothing was created.
            log.error("Unexpected failure creating provider order for paymentId={}; recovery will retry",
                    result.payment().getId(), e);
        }
        return new PaymentResult(lookup.require(result.payment().getId()), 201, false);
    }

    private PaymentResult createInTransaction(CreatePaymentCommand cmd, String key, String requestHash) {
        locks.lockPaymentCreation(cmd.merchantId());

        var replay = idempotency.findReplay(cmd.merchantId(), OPERATION, key, requestHash);
        if (replay.isPresent()) {
            return new PaymentResult(lookup.require(replay.get().resourceId()), replay.get().responseStatus(), true);
        }

        MerchantConfigReplica merchant = merchants.findById(cmd.merchantId()).filter(MerchantConfigReplica::isActive)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.MERCHANT_NOT_ACTIVE,
                        "The merchant is not active or its configuration is not available yet."));
        validateAmount(cmd.amount(), merchant);
        Duration ttl = resolveTtl(cmd.expiresInMinutes(), merchant);

        payments.findLiveByMerchantReference(cmd.merchantId(), cmd.merchantReference())
                .ifPresent(existing -> {
                    throw duplicate(cmd, existing);
                });

        Instant now = clock.instant();
        Payment payment = Payment.create(cmd.merchantId(), cmd.merchantReference(), cmd.amount(),
                merchant.getCaptureMode(), cmd.description(), cmd.customer(), cmd.returnUrl(),
                merchant.getMaxAttempts(), now.plus(ttl), MDC.get(CorrelationId.MDC_KEY));
        payments.save(payment);
        transitions.created(payment, MDC.get(CorrelationId.MDC_KEY));
        idempotency.record(cmd.merchantId(), OPERATION, key, requestHash, payment.getId(), 201);
        return new PaymentResult(payment, 201, false);
    }

    private void validateAmount(Money amount, MerchantConfigReplica merchant) {
        if (!amount.currency().equals(merchant.getCurrency())) {
            throw new BusinessException(PaymentErrorCode.CURRENCY_NOT_SUPPORTED,
                    "Only " + merchant.getCurrency() + " is accepted.");
        }
        if (amount.isLessThan(merchant.minAmount())) {
            throw new BusinessException(PaymentErrorCode.AMOUNT_BELOW_MINIMUM,
                    "Minimum amount is " + merchant.minAmount() + ".", Map.of("minAmount", merchant.minAmount()));
        }
        if (amount.isGreaterThan(merchant.maxAmountPerTxn())) {
            throw new BusinessException(PaymentErrorCode.AMOUNT_ABOVE_LIMIT,
                    "Maximum amount per payment is " + merchant.maxAmountPerTxn() + ".",
                    Map.of("maxAmountPerTransaction", merchant.maxAmountPerTxn()));
        }
        Instant startOfDay = LocalDate.now(clock.withZone(properties.businessZone()))
                .atStartOfDay(properties.businessZone()).toInstant();
        BigDecimal committed = payments.sumCommittedAmountSince(merchant.getMerchantId(), startOfDay);
        Money afterThis = new Money(committed, merchant.getCurrency()).plus(amount);
        if (afterThis.isGreaterThan(merchant.dailyLimit())) {
            throw new BusinessException(PaymentErrorCode.DAILY_LIMIT_EXCEEDED,
                    "This payment would exceed the daily limit of " + merchant.dailyLimit() + ".",
                    Map.of("dailyLimit", merchant.dailyLimit(),
                            "remainingToday", merchant.dailyLimit().minus(new Money(committed, merchant.getCurrency()))));
        }
    }

    private Duration resolveTtl(Integer requestedMinutes, MerchantConfigReplica merchant) {
        if (requestedMinutes == null) {
            return Duration.ofMinutes(merchant.getPaymentTtlMinutes());
        }
        if (requestedMinutes > merchant.getPaymentTtlMinutes()) {
            throw new BusinessException(PaymentErrorCode.INVALID_EXPIRY,
                    "expiresInMinutes may be at most " + merchant.getPaymentTtlMinutes() + ".",
                    Map.of("maxExpiresInMinutes", merchant.getPaymentTtlMinutes()));
        }
        return Duration.ofMinutes(requestedMinutes);
    }

    private void validateReturnUrl(String returnUrl) {
        if (returnUrl == null) {
            return;
        }
        try {
            URI uri = new URI(returnUrl);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            boolean schemeOk = scheme.equals("https") || (properties.allowInsecureReturnUrls() && scheme.equals("http"));
            if (!schemeOk || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new BusinessException(PaymentErrorCode.INVALID_RETURN_URL,
                        "returnUrl must be an absolute https URL without credentials.");
            }
        } catch (java.net.URISyntaxException e) {
            throw new BusinessException(PaymentErrorCode.INVALID_RETURN_URL, "returnUrl is not a valid URL.");
        }
    }

    private static BusinessException duplicate(CreatePaymentCommand cmd, Payment existing) {
        return new BusinessException(PaymentErrorCode.DUPLICATE_PAYMENT,
                "A live payment already exists for merchantReference '" + cmd.merchantReference() + "'.",
                existing == null ? Map.of() : Map.of("existingPaymentId", existing.getId(),
                        "existingStatus", existing.getStatus().name()));
    }
}
