package com.hpg.console.client.view;

import java.time.Instant;

public record ApiKeyView(String keyId, String status, Instant createdAt, Instant lastUsedAt, Instant revokedAt) {

    public boolean active() {
        return "ACTIVE".equals(status);
    }
}
