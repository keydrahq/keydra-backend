-- Migrations between two targets, kept.
--
-- They lived in a map in memory, which lost the history on every restart and — worse — made a
-- migration that was interrupted halfway indistinguishable from one that never ran. Those two
-- call for opposite decisions from whoever is deciding whether to start it again.
--
-- The row is written when the job starts, checkpointed as it walks, and finished when it ends.
-- Anything still marked RUNNING when the application starts was interrupted, because nothing
-- else could have left it that way; the startup sweep says so rather than leaving a job that
-- appears to have been running since Tuesday.

CREATE TABLE key_migration
(
    id                   VARCHAR(36)  NOT NULL,
    source_connection_id BIGINT       NOT NULL,
    target_connection_id BIGINT       NOT NULL,
    -- "match" is reserved in SQL, and the column is the glob the keys were taken with.
    match_pattern        VARCHAR(500),
    scanned              BIGINT       NOT NULL,
    migrated             BIGINT       NOT NULL,
    skipped              BIGINT       NOT NULL,
    failed               BIGINT       NOT NULL,
    deleted              BIGINT       NOT NULL,
    reason               VARCHAR(1000),
    state                VARCHAR(16)  NOT NULL,
    started_at           TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    finished_at          TIMESTAMP(6) WITH TIME ZONE,
    started_by           VARCHAR(200),
    CONSTRAINT key_migration_pkey PRIMARY KEY (id)
);

CREATE INDEX idx_key_migration_started ON key_migration (started_at);
CREATE INDEX idx_key_migration_source ON key_migration (source_connection_id);
