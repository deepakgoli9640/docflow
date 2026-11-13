package com.hpg.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * What notification-service needs to know about a merchant, replicated from {@code merchant.snapshots.v1}:
 * where to call, and whether the hospital wants patients contacted.
 *
 * <p>The signing secret is not here on purpose. It is fetched over an authenticated internal call when a
 * webhook is actually signed, so a secret never sits in a Kafka topic or in this table
 * (docs/01-architecture-overview.md §4.2).
 */
@Entity
@Table(name = "merchant_notification_replica")
public class MerchantNotificationReplica {

    @Id
    @Column(name = "merchant_id")
    private UUID merchantId;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "webhook_url", length = 2048)
    private String webhookUrl;

    @Column(name = "notify_patient_email", nullable = false)
    private boolean notifyPatientEmail;

    @Column(name = "notify_patient_sms", nullable = false)
    private boolean notifyPatientSms;

    @Column(name = "snapshot_version", nullable = false)
    private long snapshotVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MerchantNotificationReplica() {
        // for JPA
    }

    public MerchantNotificationReplica(UUID merchantId) {
        this.merchantId = merchantId;
    }

    /** @return true if applied; an older or equal snapshot version is ignored */
    public boolean apply(long version, String displayName, String status, String webhookUrl,
            boolean notifyPatientEmail, boolean notifyPatientSms, Instant now) {
        if (updatedAt != null && version <= snapshotVersion) {
            return false;
        }
        this.displayName = displayName;
        this.status = status;
        this.webhookUrl = webhookUrl;
        this.notifyPatientEmail = notifyPatientEmail;
        this.notifyPatientSms = notifyPatientSms;
        this.snapshotVersion = version;
        this.updatedAt = now;
        return true;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getStatus() {
        return status;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public boolean isNotifyPatientEmail() {
        return notifyPatientEmail;
    }

    public boolean isNotifyPatientSms() {
        return notifyPatientSms;
    }

    public long getSnapshotVersion() {
        return snapshotVersion;
    }
}
