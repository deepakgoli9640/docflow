-- Dead letters replayed by operations (/api/v1/admin/dead-letters): who, when, which record. Replaying a record
-- twice is refused unless forced; consumers deduplicate anyway, this is for the audit trail.
CREATE TABLE dead_letter_replays (
    dlt_topic       VARCHAR(100)  NOT NULL,
    dlt_partition   INT           NOT NULL,
    dlt_offset      BIGINT        NOT NULL,
    original_topic  VARCHAR(100)  NOT NULL,
    event_id        VARCHAR(64),
    replayed_by     VARCHAR(200)  NOT NULL,
    replayed_at     TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (dlt_topic, dlt_partition, dlt_offset)
);
