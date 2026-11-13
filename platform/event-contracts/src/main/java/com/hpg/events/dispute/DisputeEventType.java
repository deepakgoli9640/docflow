package com.hpg.events.dispute;

/** Events on {@code dispute.events.v1} (key = paymentId). */
public enum DisputeEventType {
    /** The patient's bank disputed a captured payment (chargeback, retrieval or fraud claim). */
    DisputeOpened,
    /** The provider deducted (part of) the disputed amount from our balance: book a chargeback. */
    DisputeFundsDeducted,
    /** The dispute was won after a deduction: the money comes back. */
    DisputeFundsReinstated,
    /** Final outcome: WON, LOST or CLOSED. */
    DisputeResolved;

    public static final int CURRENT_VERSION = 1;
}
