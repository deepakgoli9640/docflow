package com.hpg.merchant.application;

import com.hpg.merchant.domain.AuditAction;
import com.hpg.merchant.domain.Merchant;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A merchant's view of its own profile. Legal name and contact email are deliberately not editable
 * here: in a real onboarding flow they are tied to KYC checks and changed only through ops.
 */
@Service
public class MerchantProfileService {

    private final MerchantLookup lookup;
    private final AuditLogger audit;
    private final MerchantSnapshots snapshots;

    public MerchantProfileService(MerchantLookup lookup, AuditLogger audit, MerchantSnapshots snapshots) {
        this.lookup = lookup;
        this.audit = audit;
        this.snapshots = snapshots;
    }

    @PreAuthorize("hasRole('MERCHANT')")
    @Transactional(readOnly = true)
    public Merchant get(UUID merchantId) {
        return lookup.require(merchantId);
    }

    @PreAuthorize("hasRole('MERCHANT')")
    @Transactional
    public Merchant update(UUID merchantId, String displayName, String contactPhone) {
        Merchant merchant = lookup.require(merchantId);
        merchant.updateProfile(displayName, contactPhone);
        Map<String, Object> changed = new HashMap<>();
        if (displayName != null) {
            changed.put("displayName", displayName);
        }
        if (contactPhone != null) {
            changed.put("contactPhone", "changed"); // PII: record that it changed, not the value
        }
        audit.record(merchantId, AuditAction.PROFILE_UPDATED, changed);
        if (displayName != null) {
            snapshots.publish(merchant); // the display name is part of the snapshot (shown on the checkout page)
        }
        return merchant;
    }
}
