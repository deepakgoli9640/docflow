-- Disputes / chargebacks (Phase 9c). One row per provider dispute; state forward-only like payments.
CREATE TABLE disputes (
    id                    UUID          PRIMARY KEY,
    payment_id            UUID          NOT NULL REFERENCES payments (id),
    merchant_id           UUID          NOT NULL,
    provider              VARCHAR(20)   NOT NULL,
    provider_dispute_id   VARCHAR(64)   NOT NULL,
    provider_payment_id   VARCHAR(64)   NOT NULL,
    amount                NUMERIC(19,2) NOT NULL,
    currency              VARCHAR(3)    NOT NULL,
    -- What the provider has taken from our balance, as last reported, and what the ledger has been told
    -- (DisputeFundsDeducted minus DisputeFundsReinstated). They differ only inside one transaction.
    amount_deducted       NUMERIC(19,2) NOT NULL DEFAULT 0,
    deduction_announced   NUMERIC(19,2) NOT NULL DEFAULT 0,
    status                VARCHAR(20)   NOT NULL,
    reason_code           VARCHAR(64),
    phase                 VARCHAR(30),
    respond_by            TIMESTAMPTZ,
    merchant_response     VARCHAR(20),
    response_summary      VARCHAR(4000),
    version               BIGINT        NOT NULL,
    created_at            TIMESTAMPTZ   NOT NULL,
    updated_at            TIMESTAMPTZ   NOT NULL,
    resolved_at           TIMESTAMPTZ,
    CONSTRAINT ux_disputes_provider UNIQUE (provider, provider_dispute_id),
    CONSTRAINT ck_disputes_status CHECK (status IN ('OPEN', 'UNDER_REVIEW', 'WON', 'LOST', 'CLOSED')),
    CONSTRAINT ck_disputes_response CHECK (merchant_response IN ('ACCEPTED', 'CONTESTED')),
    CONSTRAINT ck_disputes_amounts CHECK (amount > 0 AND amount_deducted >= 0 AND deduction_announced >= 0)
);

CREATE INDEX ix_disputes_merchant ON disputes (merchant_id, created_at DESC);
CREATE INDEX ix_disputes_payment ON disputes (payment_id);
