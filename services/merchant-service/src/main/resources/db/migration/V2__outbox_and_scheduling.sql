-- Phase 4: merchant-service publishes merchant snapshots through the transactional outbox.

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

-- Partial index: the relay only ever looks for unpublished rows, so published history stays out of it.
CREATE INDEX ix_outbox_events_unpublished ON outbox_events (id) WHERE published_at IS NULL;

-- ShedLock: the outbox relay must run on one replica at a time, or events could be reordered.
CREATE TABLE shedlock (
    name        VARCHAR(64)  PRIMARY KEY,
    lock_until  TIMESTAMPTZ  NOT NULL,
    locked_at   TIMESTAMPTZ  NOT NULL,
    locked_by   VARCHAR(255) NOT NULL
);

-- Monotonic snapshot version per merchant. A consumer applies a snapshot only if it is newer than the
-- one it already holds, which makes redelivery and topic replay harmless.
ALTER TABLE merchants ADD COLUMN snapshot_version BIGINT NOT NULL DEFAULT 0;
