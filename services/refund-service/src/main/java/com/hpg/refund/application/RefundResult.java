package com.hpg.refund.application;

import com.hpg.refund.domain.Refund;

/** A refund plus how to answer the request that produced it. */
public record RefundResult(Refund refund, int httpStatus, boolean replayed) {
}
