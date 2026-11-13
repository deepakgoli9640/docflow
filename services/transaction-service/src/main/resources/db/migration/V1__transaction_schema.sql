-- transaction-service schema (docs/04-ledger.md). Built only from payment.events.v1 and refund.events.v1.

-- One row per payment and per refund: its current status and key facts. Mutable (status moves on).
CREATE TABLE transactions (
    id                  UUID          PRIMARY KEY,
    transaction_ref     VARCHAR(30)   NOT NULL,   -- human-friendly, e.g. TXN-20261112-000123
    merchant_id         UUID          NOT NULL,
    type                VARCHAR(10)   NOT NULL,
    payment_id          UUID          NOT NULL,
    refund_id           UUID,
    merchant_reference  VARCHAR(64),
    amount              NUMERIC(19,2) NOT NULL,
    currency            VARCHAR(3)    NOT NULL,
    status              VARCHAR(20)   NOT NULL,
    provider            VARCHAR(20),
    provider_reference  VARCHAR(64),
    failure_code        VARCHAR(64),
    failure_reason      VARCHAR(500),
    late_capture        BOOLEAN       NOT NULL DEFAULT FALSE,
    source_version      BIGINT        NOT NULL,   -- aggregateVersion of the newest event applied
    initiated_at        TIMESTAMPTZ   NOT NULL,
    completed_at        TIMESTAMPTZ,              -- captured (payment) / completed (refund)
    last_event_at       TIMESTAMPTZ   NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ux_transactions_ref UNIQUE (transaction_ref),
    CONSTRAINT ck_transactions_type CHECK (type IN ('PAYMENT', 'REFUND')),
    CONSTRAINT ck_transactions_refund_id CHECK ((type = 'REFUND') = (refund_id IS NOT NULL)),
    CONSTRAINT ck_transactions_amount_positive CHECK (amount > 0)
);

CREATE UNIQUE INDEX ux_transactions_payment ON transactions (payment_id) WHERE type = 'PAYMENT';
CREATE UNIQUE INDEX ux_transactions_refund ON transactions (refund_id) WHERE type = 'REFUND';
CREATE INDEX ix_transactions_merchant_initiated ON transactions (merchant_id, initiated_at DESC);
CREATE INDEX ix_transactions_merchant_completed ON transactions (merchant_id, completed_at) WHERE completed_at IS NOT NULL;
CREATE INDEX ix_transactions_payment_all ON transactions (payment_id);

CREATE SEQUENCE transaction_ref_seq;

-- Every event that touched a transaction: its status timeline. Append-only.
CREATE TABLE transaction_events (
    id              BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id  UUID          NOT NULL REFERENCES transactions (id),
    event_id        UUID          NOT NULL,
    event_type      VARCHAR(50)   NOT NULL,
    status          VARCHAR(20)   NOT NULL,
    source_version  BIGINT        NOT NULL,
    occurred_at     TIMESTAMPTZ   NOT NULL,
    recorded_at     TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ux_transaction_events_event UNIQUE (event_id)
);

CREATE INDEX ix_transaction_events_txn ON transaction_events (transaction_id, occurred_at, id);

-- Double-entry ledger (docs/04-ledger.md §2). Each posting = 2+ entries sharing posting_id that balance.
CREATE TABLE ledger_entries (
    id               BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    posting_id       UUID          NOT NULL,      -- = the source event id: one posting per business event
    transaction_id   UUID          NOT NULL REFERENCES transactions (id),
    posting_type     VARCHAR(20)   NOT NULL,
    account_code     VARCHAR(80)   NOT NULL,
    direction        VARCHAR(6)    NOT NULL,
    amount           NUMERIC(19,2) NOT NULL,
    currency         VARCHAR(3)    NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_ledger_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_ledger_posting_type CHECK (posting_type IN ('CAPTURE', 'REFUND', 'REVERSAL')),
    CONSTRAINT ux_ledger_entry UNIQUE (posting_id, account_code, direction)
);

CREATE INDEX ix_ledger_entries_account ON ledger_entries (account_code, created_at);
CREATE INDEX ix_ledger_entries_transaction ON ledger_entries (transaction_id, id);

CREATE FUNCTION forbid_modification() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only (% not allowed)', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();

CREATE TRIGGER trg_transaction_events_append_only
    BEFORE UPDATE OR DELETE ON transaction_events
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();

-- Deferred to commit: a posting whose debits and credits differ cannot be committed, even if a bug
-- skips the Java check.
CREATE FUNCTION check_posting_balanced() RETURNS trigger AS $$
DECLARE
    imbalance NUMERIC;
BEGIN
    SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END), 0)
      INTO imbalance
      FROM ledger_entries WHERE posting_id = NEW.posting_id;
    IF imbalance <> 0 THEN
        RAISE EXCEPTION 'Posting % is unbalanced by %', NEW.posting_id, imbalance;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_ledger_posting_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_posting_balanced();

CREATE TABLE processed_events (
    consumer_group  VARCHAR(100) NOT NULL,
    event_id        UUID         NOT NULL,
    processed_at    TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (consumer_group, event_id)
);

-- Outbox (shared messaging module). Reserved for TransactionCreated on ledger.events.v1, which the
-- reconciliation service will consume; nothing is written to it yet.
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
