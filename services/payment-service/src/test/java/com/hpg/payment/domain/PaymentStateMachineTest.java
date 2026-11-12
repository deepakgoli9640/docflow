package com.hpg.payment.domain;

import com.hpg.common.error.BusinessException;
import com.hpg.common.money.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exhaustive test: every (from, to, authority) combination — 9 x 9 x 3 = 243 cases — is checked
 * against a table written out independently of the production code. If someone adds or removes a
 * transition, this test forces them to update the expected table and think about it.
 */
class PaymentStateMachineTest {

    /** Expected legal transitions, written as "FROM>TO:AUTHORITY". */
    private static final Set<String> EXPECTED = Set.of(
            "CREATED>PENDING:PROVIDER", "CREATED>FAILED:PROVIDER", "CREATED>CANCELLED:MERCHANT", "CREATED>EXPIRED:SYSTEM",
            "PENDING>PROCESSING:PROVIDER", "PENDING>AUTHORIZED:PROVIDER", "PENDING>CAPTURED:PROVIDER",
            "PENDING>FAILED:PROVIDER", "PENDING>CANCELLED:MERCHANT", "PENDING>EXPIRED:SYSTEM",
            "PROCESSING>AUTHORIZED:PROVIDER", "PROCESSING>CAPTURED:PROVIDER", "PROCESSING>FAILED:PROVIDER",
            "AUTHORIZED>CAPTURED:PROVIDER", "AUTHORIZED>UNKNOWN:SYSTEM", "AUTHORIZED>CANCELLED:MERCHANT",
            "AUTHORIZED>EXPIRED:SYSTEM",
            "UNKNOWN>CAPTURED:PROVIDER", "UNKNOWN>AUTHORIZED:PROVIDER", "UNKNOWN>FAILED:PROVIDER",
            "EXPIRED>CAPTURED:PROVIDER", "CANCELLED>CAPTURED:PROVIDER", "FAILED>CAPTURED:PROVIDER");

    static Stream<Arguments> allCombinations() {
        List<Arguments> cases = new ArrayList<>();
        for (PaymentStatus from : PaymentStatus.values()) {
            for (PaymentStatus to : PaymentStatus.values()) {
                for (Authority authority : Authority.values()) {
                    cases.add(Arguments.of(from, to, authority));
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} -> {1} by {2}")
    @MethodSource("allCombinations")
    void everyCombinationMatchesTheExpectedTable(PaymentStatus from, PaymentStatus to, Authority authority) {
        boolean expected = EXPECTED.contains(from + ">" + to + ":" + authority);
        TransitionDecision expectedDecision = from == to ? TransitionDecision.NO_OP
                : expected ? TransitionDecision.APPLY
                : authority == Authority.PROVIDER ? TransitionDecision.IGNORE_STALE
                : TransitionDecision.REJECT;
        assertThat(PaymentStateMachine.decide(from, to, authority)).isEqualTo(expectedDecision);
    }

    @Test
    void capturedIsFinal() {
        assertThat(PaymentStateMachine.allowedTargets(PaymentStatus.CAPTURED, Authority.PROVIDER)).isEmpty();
        assertThat(PaymentStateMachine.allowedTargets(PaymentStatus.CAPTURED, Authority.MERCHANT)).isEmpty();
        assertThat(PaymentStateMachine.allowedTargets(PaymentStatus.CAPTURED, Authority.SYSTEM)).isEmpty();
    }

    @Test
    void onlyProviderEvidenceCanReviveATerminalPayment() {
        for (PaymentStatus terminal : List.of(PaymentStatus.EXPIRED, PaymentStatus.CANCELLED, PaymentStatus.FAILED)) {
            assertThat(PaymentStateMachine.allowedTargets(terminal, Authority.PROVIDER)).containsExactly(PaymentStatus.CAPTURED);
            assertThat(PaymentStateMachine.allowedTargets(terminal, Authority.MERCHANT)).isEmpty();
            assertThat(PaymentStateMachine.allowedTargets(terminal, Authority.SYSTEM)).isEmpty();
        }
    }

    private static Payment newPayment() {
        return Payment.create(UUID.randomUUID(), "BILL-1", Money.inr("20000.00"), CaptureMode.AUTO, null, null,
                null, 3, Instant.parse("2026-11-12T10:00:00Z"), "corr");
    }

    @Test
    void paymentEnforcesTheMachineAndRecordsTimestamps() {
        Payment p = newPayment();
        Instant t1 = Instant.parse("2026-11-12T09:01:00Z");
        assertThat(p.transition(PaymentStatus.PENDING, TransitionSource.PROVIDER_RESPONSE, t1)).isEqualTo(TransitionDecision.APPLY);
        assertThat(p.transition(PaymentStatus.AUTHORIZED, TransitionSource.PROVIDER_WEBHOOK, t1)).isEqualTo(TransitionDecision.APPLY);
        assertThat(p.getAuthorizedAt()).isEqualTo(t1);
        Instant t2 = t1.plusSeconds(60);
        p.transition(PaymentStatus.CAPTURED, TransitionSource.PROVIDER_RESPONSE, t2);
        assertThat(p.getCapturedAt()).isEqualTo(t2);
        assertThat(p.isLateCapture()).isFalse();
    }

    @Test
    void merchantCannotCancelACapturedPayment() {
        Payment p = newPayment();
        Instant now = Instant.now();
        p.transition(PaymentStatus.PENDING, TransitionSource.PROVIDER_RESPONSE, now);
        p.transition(PaymentStatus.CAPTURED, TransitionSource.PROVIDER_WEBHOOK, now);
        assertThatThrownBy(() -> p.transition(PaymentStatus.CANCELLED, TransitionSource.MERCHANT_API, now))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(PaymentErrorCode.INVALID_STATE_TRANSITION));
    }

    @Test
    void outOfOrderWebhookIsIgnoredNotRejected() {
        Payment p = newPayment();
        Instant now = Instant.now();
        p.transition(PaymentStatus.PENDING, TransitionSource.PROVIDER_RESPONSE, now);
        p.transition(PaymentStatus.CAPTURED, TransitionSource.PROVIDER_WEBHOOK, now);
        assertThat(p.transition(PaymentStatus.AUTHORIZED, TransitionSource.PROVIDER_WEBHOOK, now))
                .isEqualTo(TransitionDecision.IGNORE_STALE);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
    }

    @Test
    void lateCaptureAfterExpiryIsAppliedAndFlagged() {
        Payment p = newPayment();
        Instant now = Instant.now();
        p.transition(PaymentStatus.PENDING, TransitionSource.PROVIDER_RESPONSE, now);
        p.transition(PaymentStatus.EXPIRED, TransitionSource.SYSTEM, now);
        assertThat(p.transition(PaymentStatus.CAPTURED, TransitionSource.PROVIDER_POLL, now)).isEqualTo(TransitionDecision.APPLY);
        assertThat(p.isLateCapture()).isTrue();
    }

    @Test
    void repeatingATransitionIsANoOp() {
        Payment p = newPayment();
        Instant now = Instant.now();
        p.transition(PaymentStatus.PENDING, TransitionSource.PROVIDER_RESPONSE, now);
        assertThat(p.transition(PaymentStatus.PENDING, TransitionSource.PROVIDER_RESPONSE, now)).isEqualTo(TransitionDecision.NO_OP);
    }

    @Test
    void attemptStatusOnlyMovesForward() {
        assertThat(AttemptStatus.CREATED.canMoveTo(AttemptStatus.AUTHORIZED)).isTrue();
        assertThat(AttemptStatus.AUTHORIZED.canMoveTo(AttemptStatus.CAPTURED)).isTrue();
        assertThat(AttemptStatus.CAPTURED.canMoveTo(AttemptStatus.AUTHORIZED)).isFalse();
        assertThat(AttemptStatus.CAPTURED.canMoveTo(AttemptStatus.FAILED)).isFalse();
        assertThat(AttemptStatus.FAILED.canMoveTo(AttemptStatus.CAPTURED)).isFalse();
        assertThat(AttemptStatus.AUTHORIZED.canMoveTo(AttemptStatus.FAILED)).isTrue();
    }

    @Test
    void checkoutTokenIsUnguessableAndNotThePaymentId() {
        Payment p = newPayment();
        assertThat(p.getCheckoutToken()).startsWith("ck_").hasSizeGreaterThan(40).doesNotContain(p.getId().toString());
    }
}
