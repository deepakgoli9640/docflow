-- Merchant snapshots on merchant.snapshots.v1 (docs/06-kafka-events.md §2).

-- Increases with every snapshot published for the merchant; replicas ignore anything older than what
-- they hold. 0 = never published (the startup backfill publishes those).
ALTER TABLE merchants ADD COLUMN snapshot_version BIGINT NOT NULL DEFAULT 0;

-- Transactional outbox (shared messaging module): the snapshot commits with the change it describes.
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
