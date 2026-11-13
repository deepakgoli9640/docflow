-- refund-service schema. Owned exclusively by refund-service (docs/03-refund-lifecycle.md).
-- Timestamps TIMESTAMPTZ (UTC). Money NUMERIC(19,2) + currency.

-- Local replica of captured payments, fed by PaymentCaptured events. "No row" = not (yet) refundable.
CREATE TABLE refundable_payments (
    payment_id           UUID          PRIMARY KEY,
    merchant_id          UUID          NOT NULL,
    merchant_reference   VARCHAR(64),
    provider             VARCHAR(20)   NOT NULL,
    provider_payment_id  VARCHAR(64)   NOT NULL,
    currency             VARCHAR(3)    NOT NULL,
    captured_amount      NUMERIC(19,2) NOT NULL,
    reserved_amount      NUMERIC(19,2) NOT NULL DEFAULT 0,   -- refunds in flight
    refunded_amount      NUMERIC(19,2) NOT NULL DEFAULT 0,   -- refunds completed
    captured_at          TIMESTAMPTZ   NOT NULL,
    source_event_id      UUID          NOT NULL,
    version              BIGINT        NOT NULL DEFAULT 0,
    updated_at           TIMESTAMPTZ   NOT NULL,
    -- Last line of defence for the cumulative-amount guarantee (the atomic UPDATE is the first).
    CONSTRAINT ck_refundable_amounts CHECK (reserved_amount >= 0 AND refunded_amount >= 0
                                            AND refunded_amount + reserved_amount <= captured_amount),
    CONSTRAINT ck_refundable_captured_positive CHECK (captured_amount > 0)
);

CREATE INDEX ix_refundable_payments_merchant ON refundable_payments (merchant_id);

CREATE TABLE refunds (
    id                  UUID          PRIMARY KEY,
    payment_id          UUID          NOT NULL REFERENCES refundable_payments (payment_id),
    merchant_id         UUID          NOT NULL,
    amount              NUMERIC(19,2) NOT NULL,
    currency            VARCHAR(3)    NOT NULL,
    status              VARCHAR(20)   NOT NULL,
    reason              VARCHAR(255),
    initiated_by        VARCHAR(10)   NOT NULL,
    provider            VARCHAR(20)   NOT NULL,
    provider_refund_id  VARCHAR(64),
    failure_code        VARCHAR(64),
    failure_reason      VARCHAR(500),
    provider_attempts   INT           NOT NULL DEFAULT 0,
    last_attempt_at     TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    correlation_id      VARCHAR(64),
    version             BIGINT        NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ   NOT NULL,
    created_by          VARCHAR(100)  NOT NULL,
    updated_by          VARCHAR(100)  NOT NULL,
    CONSTRAINT ck_refunds_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_refunds_status CHECK (status IN ('CREATED', 'PROCESSING', 'UNKNOWN', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_refunds_initiated_by CHECK (initiated_by IN ('MERCHANT', 'OPS', 'SYSTEM')),
    CONSTRAINT ck_refunds_completed_at CHECK (status <> 'COMPLETED' OR completed_at IS NOT NULL)
);

CREATE UNIQUE INDEX ux_refunds_provider_refund ON refunds (provider, provider_refund_id)
    WHERE provider_refund_id IS NOT NULL;
CREATE INDEX ix_refunds_merchant_created ON refunds (merchant_id, created_at DESC);
CREATE INDEX ix_refunds_payment ON refunds (payment_id);
CREATE INDEX ix_refunds_recovery ON refunds (status, updated_at) WHERE status IN ('CREATED', 'PROCESSING', 'UNKNOWN');

CREATE TABLE refund_status_history (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    refund_id     UUID         NOT NULL REFERENCES refunds (id),
    from_status   VARCHAR(20),
    to_status     VARCHAR(20)  NOT NULL,
    reason        VARCHAR(500),
    actor         VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_refund_status_history_refund ON refund_status_history (refund_id, id);

CREATE FUNCTION forbid_modification() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only (% not allowed)', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_refund_status_history_append_only
    BEFORE UPDATE OR DELETE ON refund_status_history
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();

CREATE TABLE idempotency_keys (
    merchant_id      UUID         NOT NULL,
    operation        VARCHAR(30)  NOT NULL,
    idempotency_key  VARCHAR(64)  NOT NULL,
    request_hash     VARCHAR(64)  NOT NULL,
    resource_id      UUID         NOT NULL,
    response_status  INT          NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    expires_at       TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (merchant_id, operation, idempotency_key)
);

CREATE INDEX ix_idempotency_keys_expires ON idempotency_keys (expires_at);

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

CREATE TABLE processed_events (
    consumer_group  VARCHAR(100) NOT NULL,
    event_id        UUID         NOT NULL,
    processed_at    TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (consumer_group, event_id)
);

CREATE TABLE webhook_inbox (
    id                 BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider           VARCHAR(20)   NOT NULL,
    provider_event_id  VARCHAR(100)  NOT NULL,
    event_type         VARCHAR(100)  NOT NULL,
    payload            JSONB         NOT NULL,
    received_at        TIMESTAMPTZ   NOT NULL,
    outcome            VARCHAR(30),
    CONSTRAINT ux_webhook_inbox_event UNIQUE (provider, provider_event_id)
);

CREATE TABLE shedlock (
    name        VARCHAR(64)  PRIMARY KEY,
    lock_until  TIMESTAMPTZ  NOT NULL,
    locked_at   TIMESTAMPTZ  NOT NULL,
    locked_by   VARCHAR(255) NOT NULL
);
