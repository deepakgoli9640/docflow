package com.hpg.console.client.view;

import java.time.Instant;

public record MerchantView(String id, String legalName, String displayName, String contactEmail,
        String contactPhone, String status, String statusReason, Instant createdAt, Instant updatedAt) {

    public boolean canActivate() {
        return "PENDING_ACTIVATION".equals(status) || "SUSPENDED".equals(status);
    }

    public boolean canSuspend() {
        return "ACTIVE".equals(status);
    }

    public boolean canDeactivate() {
        return !"DEACTIVATED".equals(status);
    }
}
