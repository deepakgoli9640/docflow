package com.hpg.merchant.application;

import java.time.Instant;

/** A newly created API key. The only object in the system that ever holds the plaintext secret. */
public record IssuedApiKey(String keyId, String keySecret, Instant createdAt) {

    @Override
    public String toString() {
        return "IssuedApiKey[keyId=" + keyId + ", keySecret=****]";
    }
}
