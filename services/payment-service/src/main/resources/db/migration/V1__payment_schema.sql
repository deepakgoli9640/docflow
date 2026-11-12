-- payment-service schema. Owned exclusively by payment-service (docs/07-data-model.md §4).
-- Timestamps TIMESTAMPTZ (UTC). Money NUMERIC(19,2) + currency.

CREATE TABLE payments (
    id                  UUID          PRIMARY KEY,
    merchant_id         UUID          NOT NULL,
    merchant_reference  VARCHAR(64)   NOT NULL,   -- the hospital's bill reference
    amount              NUMERIC(19,2) NOT NULL,
    currency            VARCHAR(3)    NOT NULL,
    status              VARCHAR(20)   NOT NULL,
    capture_mode        VARCHAR(10)   NOT NULL,
    description         VARCHAR(255),
    customer_name       VARCHAR(100),
    customer_email_enc  VARCHAR(512),             -- AES-256-GCM
    customer_phone_enc  VARCHAR(512),             -- AES-256-GCM
    return_url          VARCHAR(2048),
    checkout_token      VARCHAR(64)   NOT NULL,
    max_attempts        INT           NOT NULL,
    expires_at          TIMESTAMPTZ   NOT NULL,
    authorized_at       TIMESTAMPTZ,
    captured_at         TIMESTAMPTZ,
    last_verified_at    TIMESTAMPTZ,
    late_capture        BOOLEAN       NOT NULL DEFAULT FALSE,
    failure_code        VARCHAR(64),
    failure_reason      VARCHAR(500),
    correlation_id      VARCHAR(64),
    version             BIGINT        NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ   NOT NULL,
    created_by          VARCHAR(100)  NOT NULL,
    updated_by          VARCHAR(100)  NOT NULL,
    CONSTRAINT ux_payments_checkout_token UNIQUE (checkout_token),
    CONSTRAINT ck_payments_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_payments_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_payments_status CHECK (status IN ('CREATED', 'PENDING', 'PROCESSING', 'AUTHORIZED', 'CAPTURED',
                                                    'FAILED', 'CANCELLED', 'EXPIRED', 'UNKNOWN')),
    CONSTRAINT ck_payments_capture_mode CHECK (capture_mode IN ('AUTO', 'MANUAL')),
    CONSTRAINT ck_payments_captured_at CHECK (status <> 'CAPTURED' OR captured_at IS NOT NULL)
);

-- Duplicate-bill protection: at most one live payment per hospital bill reference. After a payment
-- fails, is cancelled or expires, a new one for the same bill is allowed.
CREATE UNIQUE INDEX ux_payments_active_reference ON payments (merchant_id, merchant_reference)
    WHERE status NOT IN ('FAILED', 'CANCELLED', 'EXPIRED');

CREATE INDEX ix_payments_merchant_created ON payments (merchant_id, created_at DESC);
-- Recovery and expiry scans only ever look at non-terminal payments: keep that index small.
CREATE INDEX ix_payments_recovery ON payments (status, updated_at)
    WHERE status IN ('CREATED', 'PENDING', 'PROCESSING', 'AUTHORIZED', 'UNKNOWN');
CREATE INDEX ix_payments_expiry ON payments (expires_at) WHERE status IN ('CREATED', 'PENDING');

CREATE TABLE payment_orders (
    id                 UUID          PRIMARY KEY,
    payment_id         UUID          NOT NULL REFERENCES payments (id),
    provider           VARCHAR(20)   NOT NULL,
    provider_order_id  VARCHAR(64)   NOT NULL,
    amount             NUMERIC(19,2) NOT NULL,
    currency           VARCHAR(3)    NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ux_payment_orders_provider_order UNIQUE (provider, provider_order_id)
);

CREATE INDEX ix_payment_orders_payment ON payment_orders (payment_id);

CREATE TABLE payment_attempts (
    id                   UUID          PRIMARY KEY,
    payment_id           UUID          NOT NULL REFERENCES payments (id),
    payment_order_id     UUID          NOT NULL REFERENCES payment_orders (id),
    provider_payment_id  VARCHAR(64)   NOT NULL,
    method               VARCHAR(30),
    status               VARCHAR(20)   NOT NULL,
    amount               NUMERIC(19,2) NOT NULL,
    currency             VARCHAR(3)    NOT NULL,
    error_code           VARCHAR(64),
    error_description    VARCHAR(500),
    duplicate_capture    BOOLEAN       NOT NULL DEFAULT FALSE,
    version              BIGINT        NOT NULL,
    created_at           TIMESTAMPTZ   NOT NULL,
    updated_at           TIMESTAMPTZ   NOT NULL,
    created_by           VARCHAR(100)  NOT NULL,
    updated_by           VARCHAR(100)  NOT NULL,
    CONSTRAINT ux_payment_attempts_provider_payment UNIQUE (provider_payment_id),
    CONSTRAINT ck_payment_attempts_status CHECK (status IN ('CREATED', 'AUTHORIZED', 'CAPTURED', 'FAILED'))
);

CREATE INDEX ix_payment_attempts_payment ON payment_attempts (payment_id);

-- Every state change, with what triggered it. Append-only.
CREATE TABLE payment_status_history (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id    UUID         NOT NULL REFERENCES payments (id),
    from_status   VARCHAR(20),
    to_status     VARCHAR(20)  NOT NULL,
    source        VARCHAR(30)  NOT NULL,
    reason        VARCHAR(500),
    causation_id  VARCHAR(100),
    actor         VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_payment_status_history_payment ON payment_status_history (payment_id, id);

CREATE FUNCTION forbid_modification() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only (% not allowed)', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_payment_status_history_append_only
    BEFORE UPDATE OR DELETE ON payment_status_history
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();

-- Idempotency-Key records. Written in the same transaction as the business change they protect.
CREATE TABLE idempotency_keys (
    merchant_id      UUID         NOT NULL,
    operation        VARCHAR(30)  NOT NULL,
    idempotency_key  VARCHAR(64)  NOT NULL,
    request_hash     VARCHAR(64)  NOT NULL,   -- SHA-256 hex of the canonical request body
    resource_id      UUID         NOT NULL,
    response_status  INT          NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    expires_at       TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (merchant_id, operation, idempotency_key)
);

CREATE INDEX ix_idempotency_keys_expires ON idempotency_keys (expires_at);

-- Transactional outbox (relay to Kafka arrives in Phase 4).
CREATE TABLE outbox_events (
    id              BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id        UUID          NOT NULL,
    aggregate_type  VARCHAR(50)   NOT NULL,
    aggregate_id    VARCHAR(64)   NOT NULL,
    event_type      VARCHAR(100)  NOT NULL,
    topic           VARCHAR(100)  NOT NULL,
    message_key     VARCHAR(100)  NOT NULL,
    payload         JSONB         NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL,
    published_at    TIMESTAMPTZ,
    attempts        INT           NOT NULL DEFAULT 0,
    last_error      VARCHAR(1000),
    CONSTRAINT ux_outbox_events_event_id UNIQUE (event_id)
);

CREATE INDEX ix_outbox_events_unpublished ON outbox_events (id) WHERE published_at IS NULL;

-- Every provider webhook we accepted (signature valid), deduplicated by the provider's event id.
CREATE TABLE webhook_inbox (
    id                 BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider           VARCHAR(20)   NOT NULL,
    provider_event_id  VARCHAR(100)  NOT NULL,
    event_type         VARCHAR(100)  NOT NULL,
    payload            JSONB         NOT NULL,
    received_at        TIMESTAMPTZ   NOT NULL,
    processed_at       TIMESTAMPTZ,
    outcome            VARCHAR(30),
    detail             VARCHAR(500),
    CONSTRAINT ux_webhook_inbox_event UNIQUE (provider, provider_event_id)
);

-- Local copy of the merchant settings payment-service needs on every request (fed by
-- merchant.snapshots.v1 from Phase 4; seeded through a temporary OPS endpoint until then).
CREATE TABLE merchant_config_replica (
    merchant_id          UUID          PRIMARY KEY,
    display_name         VARCHAR(100)  NOT NULL,   -- shown to the patient on the checkout page
    status               VARCHAR(30)   NOT NULL,
    enabled_methods      JSONB         NOT NULL,
    capture_mode         VARCHAR(10)   NOT NULL,
    currency             VARCHAR(3)    NOT NULL,
    min_amount           NUMERIC(19,2) NOT NULL,
    max_amount_per_txn   NUMERIC(19,2) NOT NULL,
    daily_limit          NUMERIC(19,2) NOT NULL,
    payment_ttl_minutes  INT           NOT NULL,
    max_attempts         INT           NOT NULL,
    snapshot_version     BIGINT        NOT NULL,
    updated_at           TIMESTAMPTZ   NOT NULL
);

-- ShedLock: ensures each scheduled recovery job runs on one replica at a time.
CREATE TABLE shedlock (
    name        VARCHAR(64)  PRIMARY KEY,
    lock_until  TIMESTAMPTZ  NOT NULL,
    locked_at   TIMESTAMPTZ  NOT NULL,
    locked_by   VARCHAR(255) NOT NULL
);
