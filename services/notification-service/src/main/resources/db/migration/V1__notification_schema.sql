-- notification-service schema. One row per (event, channel); deliveries retried from the database.

CREATE TABLE notifications (
    id                    UUID          PRIMARY KEY,
    merchant_id           UUID          NOT NULL,
    source_event_id       UUID          NOT NULL,
    source_event_type     VARCHAR(50)   NOT NULL,
    channel               VARCHAR(10)   NOT NULL,
    public_event_type     VARCHAR(50)   NOT NULL,   -- e.g. payment.captured
    payment_id            UUID          NOT NULL,
    refund_id             UUID,
    sequence              BIGINT        NOT NULL,   -- aggregate version: lets receivers drop stale updates
    recipient_enc         VARCHAR(512),             -- EMAIL: patient address, AES-256-GCM
    subject               VARCHAR(200),
    body                  TEXT          NOT NULL,   -- WEBHOOK: JSON sent as-is; EMAIL: plain-text body
    status                VARCHAR(10)   NOT NULL,
    attempts              INT           NOT NULL DEFAULT 0,
    next_attempt_at       TIMESTAMPTZ   NOT NULL,
    last_error            VARCHAR(500),
    last_response_status  INT,
    delivered_at          TIMESTAMPTZ,
    version               BIGINT        NOT NULL,
    created_at            TIMESTAMPTZ   NOT NULL,
    updated_at            TIMESTAMPTZ   NOT NULL,
    created_by            VARCHAR(100)  NOT NULL,
    updated_by            VARCHAR(100)  NOT NULL,
    CONSTRAINT ux_notifications_event_channel UNIQUE (source_event_id, channel),
    CONSTRAINT ck_notifications_channel CHECK (channel IN ('WEBHOOK', 'EMAIL')),
    CONSTRAINT ck_notifications_status CHECK (status IN ('PENDING', 'DELIVERED', 'SKIPPED', 'DEAD'))
);

-- The dispatcher only ever scans PENDING rows that are due.
CREATE INDEX ix_notifications_due ON notifications (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX ix_notifications_merchant_created ON notifications (merchant_id, created_at DESC);
CREATE INDEX ix_notifications_payment ON notifications (payment_id);

-- One row per delivery attempt. Append-only.
CREATE TABLE notification_attempts (
    id               BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    notification_id  UUID          NOT NULL REFERENCES notifications (id),
    attempt_no       INT           NOT NULL,
    outcome          VARCHAR(20)   NOT NULL,
    http_status      INT,
    error            VARCHAR(500),
    duration_ms      BIGINT        NOT NULL,
    attempted_at     TIMESTAMPTZ   NOT NULL
);

CREATE INDEX ix_notification_attempts_notification ON notification_attempts (notification_id, id);

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
