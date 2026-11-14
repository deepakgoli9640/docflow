-- State of the SIMULATED payment provider (local demos and tests only; unused with Razorpay). Kept in the
-- database, not in memory, so it behaves like an external provider: shared by every payment-service replica and
-- kept across restarts (scripts/ha-check.sh, scripts/chaos-test.sh).
CREATE TABLE simulated_provider_objects (
    kind        VARCHAR(20)  NOT NULL,     -- ORDER, PAYMENT, DISPUTE
    id          VARCHAR(64)  NOT NULL,
    ref         VARCHAR(64),               -- PAYMENT: its order id
    data        JSONB        NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (kind, id)
);
CREATE INDEX ix_simulated_provider_ref ON simulated_provider_objects (kind, ref);
