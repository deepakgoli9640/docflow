package com.hpg.payment.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.hpg.payment.domain.Authority.MERCHANT;
import static com.hpg.payment.domain.Authority.PROVIDER;
import static com.hpg.payment.domain.Authority.SYSTEM;
import static com.hpg.payment.domain.PaymentStatus.AUTHORIZED;
import static com.hpg.payment.domain.PaymentStatus.CANCELLED;
import static com.hpg.payment.domain.PaymentStatus.CAPTURED;
import static com.hpg.payment.domain.PaymentStatus.CREATED;
import static com.hpg.payment.domain.PaymentStatus.EXPIRED;
import static com.hpg.payment.domain.PaymentStatus.FAILED;
import static com.hpg.payment.domain.PaymentStatus.PENDING;
import static com.hpg.payment.domain.PaymentStatus.PROCESSING;
import static com.hpg.payment.domain.PaymentStatus.UNKNOWN;

/**
 * The payment state machine as data: {@code from -> to -> who may trigger it}.
 *
 * <p>Keeping the rules in one table (instead of if-statements spread across services) means the whole
 * lifecycle can be read, reviewed and exhaustively tested in one place. Diagram:
 * docs/02-payment-lifecycle.md §3.2.
 *
 * <p>Two rules deserve attention:
 * <ul>
 *   <li><b>Provider-evidence override.</b> {@code EXPIRED/CANCELLED/FAILED -> CAPTURED} is allowed only
 *       for {@link Authority#PROVIDER}: if the provider says money was captured, our state must converge to
 *       that truth (flagged as a late capture), otherwise the ledger would lie.</li>
 *   <li><b>Stale provider events are ignored, not rejected.</b> Webhooks arrive out of order; an
 *       "authorized" after "captured" must be acknowledged (so the provider stops retrying) and ignored.</li>
 * </ul>
 */
public final class PaymentStateMachine {

    private static final Map<PaymentStatus, Map<PaymentStatus, Set<Authority>>> RULES = new EnumMap<>(PaymentStatus.class);

    static {
        allow(CREATED, PENDING, PROVIDER);            // provider order created
        allow(CREATED, FAILED, PROVIDER);             // provider rejected the order (non-retryable)
        allow(CREATED, CANCELLED, MERCHANT);
        allow(CREATED, EXPIRED, SYSTEM);

        allow(PENDING, PROCESSING, PROVIDER);         // signed checkout callback
        allow(PENDING, AUTHORIZED, PROVIDER);
        allow(PENDING, CAPTURED, PROVIDER);
        allow(PENDING, FAILED, PROVIDER);             // max attempts reached
        allow(PENDING, CANCELLED, MERCHANT);
        allow(PENDING, EXPIRED, SYSTEM);              // only after the provider confirms it is unpaid

        allow(PROCESSING, AUTHORIZED, PROVIDER);
        allow(PROCESSING, CAPTURED, PROVIDER);
        allow(PROCESSING, FAILED, PROVIDER);

        allow(AUTHORIZED, CAPTURED, PROVIDER);
        allow(AUTHORIZED, UNKNOWN, SYSTEM);           // capture call timed out: outcome unknown
        allow(AUTHORIZED, CANCELLED, MERCHANT);       // provider auto-reverses uncaptured authorizations
        allow(AUTHORIZED, EXPIRED, SYSTEM);           // capture window elapsed

        allow(UNKNOWN, CAPTURED, PROVIDER);           // verification found the capture
        allow(UNKNOWN, AUTHORIZED, PROVIDER);         // verification: capture did not happen
        allow(UNKNOWN, FAILED, PROVIDER);

        allow(EXPIRED, CAPTURED, PROVIDER);           // late capture (provider-evidence override)
        allow(CANCELLED, CAPTURED, PROVIDER);         // late capture
        allow(FAILED, CAPTURED, PROVIDER);            // late capture
    }

    private PaymentStateMachine() {
    }

    private static void allow(PaymentStatus from, PaymentStatus to, Authority... authorities) {
        RULES.computeIfAbsent(from, k -> new EnumMap<>(PaymentStatus.class))
                .put(to, Collections.unmodifiableSet(EnumSet.of(authorities[0], authorities)));
    }

    public static TransitionDecision decide(PaymentStatus from, PaymentStatus to, Authority authority) {
        if (from == to) {
            return TransitionDecision.NO_OP;
        }
        if (isAllowed(from, to, authority)) {
            return TransitionDecision.APPLY;
        }
        return authority == PROVIDER ? TransitionDecision.IGNORE_STALE : TransitionDecision.REJECT;
    }

    public static boolean isAllowed(PaymentStatus from, PaymentStatus to, Authority authority) {
        return RULES.getOrDefault(from, Map.of()).getOrDefault(to, Set.of()).contains(authority);
    }

    /** Targets reachable from {@code from} by {@code authority} — used in error messages. */
    public static Set<PaymentStatus> allowedTargets(PaymentStatus from, Authority authority) {
        Set<PaymentStatus> result = EnumSet.noneOf(PaymentStatus.class);
        RULES.getOrDefault(from, Map.of()).forEach((to, who) -> {
            if (who.contains(authority)) {
                result.add(to);
            }
        });
        return result;
    }

    /** True for transitions that capture money after we had given up on the payment. */
    public static boolean isLateCapture(PaymentStatus from, PaymentStatus to) {
        return to == CAPTURED && (from == EXPIRED || from == CANCELLED || from == FAILED);
    }
}
