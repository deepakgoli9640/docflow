package com.hpg.merchant.application;

import com.hpg.common.crypto.SecureTokens;

final class WebhookSecrets {

    private WebhookSecrets() {
    }

    /** {@code whsec_} + 256 random bits. The prefix makes leaked secrets easy to find with secret scanners. */
    static String generate() {
        return "whsec_" + SecureTokens.urlSafe(32);
    }
}
