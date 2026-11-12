package com.hpg.merchant.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Merchant lifecycle.
 *
 * <pre>
 * PENDING_ACTIVATION --activate--> ACTIVE --suspend--> SUSPENDED
 *          |                         ^                    |
 *          |                         +----activate--------+
 *          +------ deactivate (from any non-terminal) ---> DEACTIVATED (terminal)
 * </pre>
 *
 * A PENDING_ACTIVATION merchant may log in and configure its account but cannot take payments.
 */
public enum MerchantStatus {

    PENDING_ACTIVATION,
    ACTIVE,
    SUSPENDED,
    DEACTIVATED;

    public Set<MerchantStatus> allowedNext() {
        return switch (this) {
            case PENDING_ACTIVATION -> EnumSet.of(ACTIVE, DEACTIVATED);
            case ACTIVE -> EnumSet.of(SUSPENDED, DEACTIVATED);
            case SUSPENDED -> EnumSet.of(ACTIVE, DEACTIVATED);
            case DEACTIVATED -> EnumSet.noneOf(MerchantStatus.class);
        };
    }

    public boolean canTransitionTo(MerchantStatus target) {
        return allowedNext().contains(target);
    }

    /** Whether API clients of this merchant may obtain tokens. */
    public boolean allowsLogin() {
        return this == PENDING_ACTIVATION || this == ACTIVE;
    }
}
