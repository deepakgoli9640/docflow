package com.hpg.payment.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.function.Supplier;

/**
 * Re-runs a whole transaction when it loses an optimistic-lock race.
 *
 * <p>The checkout callback, a webhook and a recovery job can update the same payment at the same
 * moment. With {@code @Version}, the slower writer fails instead of silently overwriting. Re-running
 * the transaction re-reads the payment and re-applies the transition; because transitions are
 * idempotent and forward-only, the second run is either a no-op or a legal move.
 * The supplier MUST start its own transaction (call it from outside any transaction).
 */
public final class OptimisticRetry {

    private static final Logger log = LoggerFactory.getLogger(OptimisticRetry.class);
    private static final int MAX_ATTEMPTS = 3;

    private OptimisticRetry() {
    }

    public static <T> T run(String what, Supplier<T> transaction) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transaction.get();
            } catch (OptimisticLockingFailureException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                log.info("Optimistic lock conflict in {} (attempt {}), retrying", what, attempt);
            }
        }
    }
}
