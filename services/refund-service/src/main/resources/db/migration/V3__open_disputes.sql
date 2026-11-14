-- Payments under an open dispute (from dispute.events.v1). A refund would pay the patient twice if the
-- chargeback is lost, so refunds wait until the dispute is decided. Keyed by dispute: independent of whether
-- the PaymentCaptured event has arrived yet.
CREATE TABLE open_disputes (
    dispute_id   UUID          PRIMARY KEY,
    payment_id   UUID          NOT NULL,
    merchant_id  UUID          NOT NULL,
    opened_at    TIMESTAMPTZ   NOT NULL
);
CREATE INDEX ix_open_disputes_payment ON open_disputes (payment_id);
