package com.hpg.payment.domain;

/**
 * Who is entitled to make a transition. Separating this from <em>where</em> the trigger came from
 * (see {@link TransitionSource}) lets one rule say, for example, "only provider evidence may turn an
 * expired payment into a captured one".
 */
public enum Authority {
    /** The hospital, through our API (cancel). */
    MERCHANT,
    /** Evidence from the payment provider: API response, signed callback, signed webhook, status poll. */
    PROVIDER,
    /** Our own timers and inferences (expiry, "capture outcome unknown"). */
    SYSTEM
}
