-- Phase 62: when it stopped answering, and when it started again.
--
-- Phase 49 kept the last answer and only the last, on the grounds that a history of reachability is
-- rows worth nothing an hour later. That is true of a row per check — six an hour per subject, each
-- saying what the one before it said. It is the opposite of true for a row per change: a handful a
-- year for something that works, and worth more the older it gets.
--
-- The edge was already being computed to decide whether to send a message, and thrown away.

CREATE TABLE reachability_event
(
    id         BIGINT       NOT NULL,
    -- The same two columns the current answer is keyed by, and for the same reason: the kinds live
    -- in different tables, so there is nothing one foreign key could point at.
    kind       VARCHAR(64)  NOT NULL,
    subject_id BIGINT       NOT NULL,
    -- The name as it was when this happened.
    --
    -- Not resolved when the timeline is read, which would leave a deleted destination as a bare id
    -- and a renamed one rewriting what happened under its old name. It is also the name the message
    -- said at the time, so the channel and the page agree about what a thing was called.
    name       VARCHAR(200),
    at         TIMESTAMP    NOT NULL,
    ok         BOOLEAN      NOT NULL,
    -- What it said when it did not answer. A sentence, never a credential.
    detail     VARCHAR(500),
    CONSTRAINT reachability_event_pkey PRIMARY KEY (id)
);

-- Newest first is how it is read, and per subject is how it is narrowed.
CREATE INDEX idx_reachability_event_at ON reachability_event (at DESC);
CREATE INDEX idx_reachability_event_subject ON reachability_event (kind, subject_id, at DESC);

-- Increment 50 to match Hibernate's default allocation size, as elsewhere in this schema.
CREATE SEQUENCE reachability_event_seq START WITH 1 INCREMENT BY 50;
