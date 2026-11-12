-- merchant-service schema. Owned exclusively by merchant-service (see docs/07-data-model.md).
-- All timestamps are TIMESTAMPTZ and written in UTC. Money is NUMERIC(19,2).

CREATE TABLE merchants (
    id              UUID         PRIMARY KEY,
    legal_name      VARCHAR(200) NOT NULL,
    display_name    VARCHAR(100) NOT NULL,
    contact_email   VARCHAR(254) NOT NULL,
    contact_phone   VARCHAR(20),
    status          VARCHAR(30)  NOT NULL,
    status_reason   VARCHAR(500),
    version         BIGINT       NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    created_by      VARCHAR(100) NOT NULL,
    updated_by      VARCHAR(100) NOT NULL,
    CONSTRAINT ux_merchants_contact_email UNIQUE (contact_email),
    CONSTRAINT ck_merchants_status
        CHECK (status IN ('PENDING_ACTIVATION', 'ACTIVE', 'SUSPENDED', 'DEACTIVATED'))
);

CREATE INDEX ix_merchants_status_created ON merchants (status, created_at DESC);

CREATE TABLE merchant_credentials (
    id            UUID         PRIMARY KEY,
    merchant_id   UUID         NOT NULL REFERENCES merchants (id),
    key_id        VARCHAR(40)  NOT NULL,
    secret_hash   VARCHAR(100) NOT NULL,   -- bcrypt; the plaintext secret is never stored
    status        VARCHAR(20)  NOT NULL,
    expires_at    TIMESTAMPTZ,
    last_used_at  TIMESTAMPTZ,
    revoked_at    TIMESTAMPTZ,
    revoked_by    VARCHAR(100),
    version       BIGINT       NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    created_by    VARCHAR(100) NOT NULL,
    updated_by    VARCHAR(100) NOT NULL,
    CONSTRAINT ux_merchant_credentials_key_id UNIQUE (key_id),
    CONSTRAINT ck_merchant_credentials_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_merchant_credentials_revoked
        CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL))
);

CREATE INDEX ix_merchant_credentials_merchant ON merchant_credentials (merchant_id, status);

CREATE TABLE merchant_configurations (
    merchant_id           UUID          PRIMARY KEY REFERENCES merchants (id),
    enabled_methods       JSONB         NOT NULL,
    capture_mode          VARCHAR(10)   NOT NULL,
    currency              VARCHAR(3)    NOT NULL,
    min_amount            NUMERIC(19,2) NOT NULL,
    max_amount_per_txn    NUMERIC(19,2) NOT NULL,
    daily_limit           NUMERIC(19,2) NOT NULL,
    payment_ttl_minutes   INT           NOT NULL,
    max_attempts          INT           NOT NULL,
    refund_window_days    INT           NOT NULL,
    webhook_url           VARCHAR(2048),
    webhook_secret_enc    VARCHAR(512),  -- AES-256-GCM; must be readable to sign outgoing webhooks
    notify_patient_email  BOOLEAN       NOT NULL,
    notify_patient_sms    BOOLEAN       NOT NULL,
    version               BIGINT        NOT NULL,
    created_at            TIMESTAMPTZ   NOT NULL,
    updated_at            TIMESTAMPTZ   NOT NULL,
    created_by            VARCHAR(100)  NOT NULL,
    updated_by            VARCHAR(100)  NOT NULL,
    CONSTRAINT ck_merchant_configurations_capture_mode CHECK (capture_mode IN ('AUTO', 'MANUAL')),
    CONSTRAINT ck_merchant_configurations_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_merchant_configurations_limits
        CHECK (min_amount > 0 AND min_amount <= max_amount_per_txn AND max_amount_per_txn <= daily_limit),
    CONSTRAINT ck_merchant_configurations_positive
        CHECK (payment_ttl_minutes > 0 AND max_attempts > 0 AND refund_window_days > 0)
);

-- Append-only audit trail of security-relevant actions.
CREATE TABLE merchant_audit_log (
    id              BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    merchant_id     UUID         REFERENCES merchants (id),
    action          VARCHAR(50)  NOT NULL,
    actor           VARCHAR(100) NOT NULL,
    correlation_id  VARCHAR(64),
    details         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_merchant_audit_log_merchant ON merchant_audit_log (merchant_id, created_at DESC);

CREATE FUNCTION forbid_audit_log_modification() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'merchant_audit_log is append-only (% not allowed)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_merchant_audit_log_append_only
    BEFORE UPDATE OR DELETE ON merchant_audit_log
    FOR EACH ROW EXECUTE FUNCTION forbid_audit_log_modification();
