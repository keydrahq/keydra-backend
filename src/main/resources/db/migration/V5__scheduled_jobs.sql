-- Phase 10: work arranged to happen on its own.
--
-- A job, a target, a cadence and a switch, plus one row per attempt. What a particular
-- kind of work needs beyond that is JSON rather than columns: the job types have almost
-- nothing in common except being worth repeating, and a column each would make this a
-- table that grows every time one is added.

CREATE TABLE scheduled_job
(
    id            BIGINT       NOT NULL,
    name          VARCHAR(200) NOT NULL,
    connection_id BIGINT       NOT NULL,
    job_type      VARCHAR(32)  NOT NULL,
    cron          VARCHAR(120) NOT NULL,
    enabled       BOOLEAN      NOT NULL,
    settings      VARCHAR(4000) NOT NULL,
    -- Whose access this runs with, checked again at every run. A username rather than an
    -- id so it reads as itself in a log.
    created_by    VARCHAR(200),
    created_at    TIMESTAMP    NOT NULL,
    last_run_at   TIMESTAMP,
    last_outcome  VARCHAR(16),
    CONSTRAINT scheduled_job_pkey PRIMARY KEY (id),
    CONSTRAINT scheduled_job_connection_fk FOREIGN KEY (connection_id)
        REFERENCES connection_profile (id) ON DELETE CASCADE
);

CREATE INDEX idx_scheduled_job_connection ON scheduled_job (connection_id);
CREATE INDEX idx_scheduled_job_enabled ON scheduled_job (enabled);

CREATE TABLE job_run
(
    id          BIGINT      NOT NULL,
    job_id      BIGINT      NOT NULL,
    started_at  TIMESTAMP   NOT NULL,
    finished_at TIMESTAMP,
    outcome     VARCHAR(16) NOT NULL,
    detail      VARCHAR(500),
    was_manual  BOOLEAN     NOT NULL,
    CONSTRAINT job_run_pkey PRIMARY KEY (id),
    CONSTRAINT job_run_job_fk FOREIGN KEY (job_id)
        REFERENCES scheduled_job (id) ON DELETE CASCADE
);

CREATE INDEX idx_job_run_job ON job_run (job_id);
CREATE INDEX idx_job_run_started ON job_run (started_at);

-- Increment 50 to match Hibernate's default allocation size, as elsewhere in this schema.
CREATE SEQUENCE scheduled_job_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE job_run_seq START WITH 1 INCREMENT BY 50;
