-- What refund-service needs to know about a merchant, fed by merchant.snapshots.v1.
CREATE TABLE merchant_replica (
    merchant_id         UUID         PRIMARY KEY,
    status              VARCHAR(30)  NOT NULL,
    refund_window_days  INT          NOT NULL,
    snapshot_version    BIGINT       NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL
);
