-- reconciliation-service schema (docs/05-reconciliation.md, docs/07-data-model.md §8).
-- Reconciliation detects and reports. It never changes payments, refunds or the ledger.

-- Our side: every ledger booking, from ledger.events.v1 (TransactionCreated).
CREATE TABLE internal_transactions (
    transaction_ref     VARCHAR(30)   PRIMARY KEY,
    transaction_id      UUID          NOT NULL,
    merchant_id         UUID          NOT NULL,
    transaction_type    VARCHAR(10)   NOT NULL,
    payment_id          UUID          NOT NULL,
    refund_id           UUID,
    provider            VARCHAR(20),
    provider_reference  VARCHAR(64),
    amount              NUMERIC(19,2) NOT NULL,
    currency            VARCHAR(3)    NOT NULL,
    posted_at           TIMESTAMPTZ   NOT NULL,
    received_at         TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_internal_type CHECK (transaction_type IN ('PAYMENT', 'REFUND'))
);

CREATE INDEX ix_internal_transactions_reference ON internal_transactions (provider_reference);
CREATE INDEX ix_internal_transactions_posted ON internal_transactions (posted_at);

-- One reconciliation run for a business date. Re-running a date creates a new batch; the newest completed
-- batch of a date is the current view, older ones are history.
CREATE TABLE reconciliation_batches (
    id                         UUID          PRIMARY KEY,
    business_date              DATE          NOT NULL,
    source                     VARCHAR(10)   NOT NULL,
    provider                   VARCHAR(20)   NOT NULL,
    file_name                  VARCHAR(255),
    file_sha256                VARCHAR(64),
    status                     VARCHAR(10)   NOT NULL,
    provider_row_count         INT           NOT NULL DEFAULT 0,
    matched_count              INT           NOT NULL DEFAULT 0,
    mismatched_count           INT           NOT NULL DEFAULT 0,
    missing_in_provider_count  INT           NOT NULL DEFAULT 0,
    missing_internal_count     INT           NOT NULL DEFAULT 0,
    duplicate_count            INT           NOT NULL DEFAULT 0,
    pending_review_count       INT           NOT NULL DEFAULT 0,
    malformed_row_count        INT           NOT NULL DEFAULT 0,
    failure_reason             VARCHAR(500),
    started_at                 TIMESTAMPTZ   NOT NULL,
    completed_at               TIMESTAMPTZ,
    version                    BIGINT        NOT NULL,
    created_at                 TIMESTAMPTZ   NOT NULL,
    updated_at                 TIMESTAMPTZ   NOT NULL,
    created_by                 VARCHAR(100)  NOT NULL,
    updated_by                 VARCHAR(100)  NOT NULL,
    CONSTRAINT ux_batches_file UNIQUE (file_sha256),   -- the same settlement file cannot be loaded twice
    CONSTRAINT ck_batches_source CHECK (source IN ('FILE', 'API')),
    CONSTRAINT ck_batches_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX ix_batches_date ON reconciliation_batches (business_date, started_at DESC);

-- Provider side, exactly as received (raw row kept for audit). Append-only.
CREATE TABLE settlement_records (
    id                   BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    batch_id             UUID          NOT NULL REFERENCES reconciliation_batches (id),
    row_number           INT           NOT NULL,
    provider_entity_id   VARCHAR(64),
    entity_type          VARCHAR(20),
    provider_order_id    VARCHAR(64),
    amount               NUMERIC(19,2),
    fee                  NUMERIC(19,2),
    tax                  NUMERIC(19,2),
    currency             VARCHAR(3),
    settlement_id        VARCHAR(64),
    settled_at           TIMESTAMPTZ,
    provider_created_at  TIMESTAMPTZ,
    raw_row              TEXT,
    parse_error          VARCHAR(500)
);

CREATE INDEX ix_settlement_records_batch ON settlement_records (batch_id, row_number);

-- The result: one row per provider row and per internal transaction missing at the provider.
CREATE TABLE reconciliation_records (
    id                        UUID          PRIMARY KEY,
    batch_id                  UUID          NOT NULL REFERENCES reconciliation_batches (id),
    status                    VARCHAR(30)   NOT NULL,
    entity_type               VARCHAR(20),
    provider_reference        VARCHAR(64),
    internal_transaction_ref  VARCHAR(30),
    merchant_id               UUID,
    internal_amount           NUMERIC(19,2),
    provider_amount           NUMERIC(19,2),
    currency                  VARCHAR(3),
    settlement_record_id      BIGINT        REFERENCES settlement_records (id),
    detail                    VARCHAR(500),
    resolution_status         VARCHAR(10),
    resolution_type           VARCHAR(30),
    resolution_note           VARCHAR(1000),
    correction_reference      VARCHAR(64),
    resolved_by               VARCHAR(100),
    resolved_at               TIMESTAMPTZ,
    version                   BIGINT        NOT NULL,
    created_at                TIMESTAMPTZ   NOT NULL,
    updated_at                TIMESTAMPTZ   NOT NULL,
    created_by                VARCHAR(100)  NOT NULL,
    updated_by                VARCHAR(100)  NOT NULL,
    CONSTRAINT ck_records_status CHECK (status IN ('MATCHED', 'MISMATCHED', 'MISSING_IN_PROVIDER',
                                                   'MISSING_IN_INTERNAL_SYSTEM', 'DUPLICATE', 'PENDING_REVIEW')),
    -- Matched rows have no workflow; every discrepancy does.
    CONSTRAINT ck_records_resolution CHECK ((status = 'MATCHED') = (resolution_status IS NULL)),
    CONSTRAINT ck_records_resolution_status CHECK (resolution_status IN ('OPEN', 'IN_REVIEW', 'RESOLVED')),
    CONSTRAINT ck_records_resolution_type CHECK (resolution_type IN ('PROVIDER_ERROR_RAISED',
                                                 'INTERNAL_CORRECTION_POSTED', 'TIMING_DIFFERENCE', 'ACCEPTED_AS_IS')),
    CONSTRAINT ck_records_resolved CHECK (resolution_status <> 'RESOLVED'
                                          OR (resolution_type IS NOT NULL AND resolved_at IS NOT NULL))
);

CREATE INDEX ix_records_batch_status ON reconciliation_records (batch_id, status);
CREATE INDEX ix_records_internal_ref ON reconciliation_records (internal_transaction_ref);
CREATE INDEX ix_records_provider_ref ON reconciliation_records (provider_reference);
CREATE INDEX ix_records_open ON reconciliation_records (resolution_status) WHERE resolution_status <> 'RESOLVED';

CREATE FUNCTION forbid_modification() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only (% not allowed)', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_settlement_records_append_only
    BEFORE UPDATE OR DELETE ON settlement_records
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();

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

CREATE TABLE shedlock (
    name        VARCHAR(64)  PRIMARY KEY,
    lock_until  TIMESTAMPTZ  NOT NULL,
    locked_at   TIMESTAMPTZ  NOT NULL,
    locked_by   VARCHAR(255) NOT NULL
);
