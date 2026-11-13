-- notification-service schema (docs/07-data-model.md §7). Owned exclusively by notification-service.

CREATE TABLE notifications (
    id               UUID         PRIMARY KEY,
    source_event_id  UUID         NOT NULL,   -- the event that caused this notification
    event_type       VARCHAR(100) NOT NULL,
    channel          VARCHAR(20)  NOT NULL,
    recipient_type   VARCHAR(20)  NOT NULL,
    recipient_enc    VARCHAR(2048),           -- AES-256-GCM: a patient email/phone or a hospital URL
    merchant_id      UUID         NOT NULL,
    payment_id       UUID,
    sequence_no      BIGINT       NOT NULL,   -- lets a hospital ignore an update older than one it has
    payload          JSONB        NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    attempt_count    INT          NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ,
    last_error       VARCHAR(1000),
    delivered_at     TIMESTAMPTZ,
    version          BIGINT       NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    created_by       VARCHAR(100) NOT NULL,
    updated_by       VARCHAR(100) NOT NULL,
    -- One notification per (event, channel, recipient). Consuming the same event twice - a redelivery,
    -- a replayed partition, a dead-letter replay - cannot send a hospital or a patient a second copy.
    CONSTRAINT ux_notifications_source UNIQUE (source_event_id, channel, recipient_type),
    CONSTRAINT ck_notifications_channel CHECK (channel IN ('WEBHOOK', 'EMAIL', 'SMS')),
    CONSTRAINT ck_notifications_recipient CHECK (recipient_type IN ('MERCHANT', 'PATIENT', 'OPS')),
    CONSTRAINT ck_notifications_status
        CHECK (status IN ('PENDING', 'SENDING', 'DELIVERED', 'RETRYING', 'DEAD', 'SKIPPED')),
    CONSTRAINT ck_notifications_delivered CHECK ((status = 'DELIVERED') = (delivered_at IS NOT NULL))
);

-- The dispatcher asks one question: what is due now? This index answers it without scanning history.
CREATE INDEX ix_notifications_due ON notifications (next_attempt_at)
    WHERE status IN ('PENDING', 'RETRYING');
CREATE INDEX ix_notifications_merchant ON notifications (merchant_id, created_at DESC);
CREATE INDEX ix_notifications_payment ON notifications (payment_id);

CREATE TABLE notification_attempts (
    id               UUID         PRIMARY KEY,
    notification_id  UUID         NOT NULL REFERENCES notifications (id),
    attempt_no       INT          NOT NULL,
    outcome          VARCHAR(20)  NOT NULL,
    response_code    INT,
    error            VARCHAR(1000),
    duration_ms      BIGINT       NOT NULL,
    attempted_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ux_notification_attempts UNIQUE (notification_id, attempt_no),
    CONSTRAINT ck_notification_attempts_outcome
        CHECK (outcome IN ('DELIVERED', 'RETRYABLE_FAILURE', 'PERMANENT_FAILURE'))
);

CREATE INDEX ix_notification_attempts_notification ON notification_attempts (notification_id, attempt_no);

CREATE FUNCTION forbid_modification() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only (% not allowed)', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

-- Delivery history is evidence of what we told a hospital and when; it is never rewritten.
CREATE TRIGGER trg_notification_attempts_append_only
    BEFORE UPDATE OR DELETE ON notification_attempts
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();

-- What this service needs to know about a merchant, replicated from merchant.snapshots.v1.
-- The webhook signing secret is deliberately not here: it is fetched over an authenticated call.
CREATE TABLE merchant_notification_replica (
    merchant_id           UUID         PRIMARY KEY,
    display_name          VARCHAR(100) NOT NULL,
    status                VARCHAR(30)  NOT NULL,
    webhook_url           VARCHAR(2048),
    notify_patient_email  BOOLEAN      NOT NULL,
    notify_patient_sms    BOOLEAN      NOT NULL,
    snapshot_version      BIGINT       NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL
);

-- Consumer-side deduplication: claimed in the same transaction as the effect of the event.
CREATE TABLE processed_events (
    consumer_group  VARCHAR(100) NOT NULL,
    event_id        UUID         NOT NULL,
    processed_at    TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (consumer_group, event_id)
);

CREATE INDEX ix_processed_events_processed_at ON processed_events (processed_at);

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

CREATE TABLE shedlock (
    name        VARCHAR(64)  PRIMARY KEY,
    lock_until  TIMESTAMPTZ  NOT NULL,
    locked_at   TIMESTAMPTZ  NOT NULL,
    locked_by   VARCHAR(255) NOT NULL
);
