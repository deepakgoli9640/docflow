package com.hpg.merchant.domain;

import com.hpg.common.error.BusinessException;
import com.hpg.common.id.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** A hospital registered with the gateway. Aggregate root of merchant-service. */
@Entity
@Table(name = "merchants")
public class Merchant extends AuditableEntity {

    @Id
    private UUID id;

    @Column(name = "legal_name", nullable = false, length = 200)
    private String legalName;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "contact_email", nullable = false, length = 254)
    private String contactEmail;

    @Column(name = "contact_phone", length = 20)
    private String contactPhone;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private MerchantStatus status;

    @Column(name = "status_reason", length = 500)
    private String statusReason;

    protected Merchant() {
        // for JPA
    }

    private Merchant(UUID id, String legalName, String displayName, String contactEmail, String contactPhone) {
        this.id = id;
        this.legalName = legalName;
        this.displayName = displayName;
        this.contactEmail = contactEmail;
        this.contactPhone = contactPhone;
        this.status = MerchantStatus.PENDING_ACTIVATION;
    }

    public static Merchant register(String legalName, String displayName, String contactEmail, String contactPhone) {
        Objects.requireNonNull(legalName, "legalName");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(contactEmail, "contactEmail");
        return new Merchant(Ids.newId(), legalName.trim(), displayName.trim(), normaliseEmail(contactEmail),
                contactPhone == null ? null : contactPhone.trim());
    }

    /** Emails are compared case-insensitively; store one canonical form so the unique constraint works. */
    public static String normaliseEmail(String email) {
        return email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public void changeStatus(MerchantStatus target, String reason) {
        if (!status.canTransitionTo(target)) {
            throw new BusinessException(MerchantErrorCode.INVALID_STATE_TRANSITION,
                    "Merchant cannot move from " + status + " to " + target + ".",
                    Map.of("currentStatus", status.name(), "allowedTransitions", status.allowedNext()));
        }
        this.status = target;
        this.statusReason = reason;
    }

    public void updateProfile(String displayName, String contactPhone) {
        requireNotDeactivated();
        if (displayName != null) {
            this.displayName = displayName.trim();
        }
        if (contactPhone != null) {
            this.contactPhone = contactPhone.trim();
        }
    }

    public void requireNotDeactivated() {
        if (status == MerchantStatus.DEACTIVATED) {
            throw new BusinessException(MerchantErrorCode.MERCHANT_DEACTIVATED, "Merchant " + id + " is deactivated.");
        }
    }

    public UUID getId() {
        return id;
    }

    public String getLegalName() {
        return legalName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public MerchantStatus getStatus() {
        return status;
    }

    public String getStatusReason() {
        return statusReason;
    }
}
