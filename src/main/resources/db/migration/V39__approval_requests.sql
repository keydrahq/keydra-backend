-- Phase 60: operations that wait for a second person.
--
-- Phase 59 made a target ask to be named before anything empties it, which answers "is this the
-- server I think it is". It does not answer "should this happen at all", and the person best placed
-- to answer that is never the person about to do it.
--
-- The row is the operation rather than a note about one. Everything it needs is written down when
-- it is asked for and exists nowhere else, so what an approver read is what runs — an unlock the
-- requester then re-used would be a signature on a document somebody rewrote afterwards.

CREATE TABLE approval_request
(
    id                    BIGINT       NOT NULL,
    kind                  VARCHAR(32)  NOT NULL,
    state                 VARCHAR(16)  NOT NULL,
    -- The target the operation is about, and the other end where there is one: a migration is two
    -- servers, and whoever approves it has to hold the permission on both.
    connection_id         BIGINT       NOT NULL,
    second_connection_id  BIGINT,
    -- What the operation will do, as JSON, encrypted.
    --
    -- Encrypted because of what is in it. For an import it is the dumped values themselves —
    -- somebody's data sitting in Keydra's database until a colleague gets back from lunch — and for
    -- a bulk delete it is a list of key names, which everything else in this application already
    -- treats as the contents of somebody's target. TEXT rather than a width: a selection is as long
    -- as it is, and truncating one would approve something other than what was asked for.
    payload               TEXT         NOT NULL,
    -- Whose access it runs with, resolved again at the moment it runs. A username rather than an
    -- id, as the schedules do it, so it survives an account being recreated and reads as itself.
    requested_by          VARCHAR(200),
    requested_at          TIMESTAMP    NOT NULL,
    -- When it stops being answerable. A purge approved three weeks late is approved against a
    -- keyspace nobody remembers.
    expires_at            TIMESTAMP    NOT NULL,
    decided_by            VARCHAR(200),
    decided_at            TIMESTAMP,
    -- Why it was declined, or what happened when it ran. One column: a request has one ending.
    detail                VARCHAR(1000),
    CONSTRAINT approval_request_pkey PRIMARY KEY (id),
    CONSTRAINT approval_request_connection_fk FOREIGN KEY (connection_id)
        REFERENCES connection_profile (id) ON DELETE CASCADE,
    -- Not cascading: losing the other end of a migration must not silently delete the record that
    -- somebody asked for one.
    CONSTRAINT approval_request_second_fk FOREIGN KEY (second_connection_id)
        REFERENCES connection_profile (id) ON DELETE SET NULL
);

CREATE INDEX idx_approval_request_state ON approval_request (state);
CREATE INDEX idx_approval_request_connection ON approval_request (connection_id);

-- Increment 50 to match Hibernate's default allocation size, as elsewhere in this schema.
CREATE SEQUENCE approval_request_seq START WITH 1 INCREMENT BY 50;

-- A target that will not be emptied by one person on their own.
--
-- Its own column beside `guarded` rather than folded into it. One asks which server this is and the
-- other asks whether it should happen; an installation that wants the second should not have to
-- accept the first to get it.
--
-- DEFAULT FALSE and NOT NULL, for phase 59's reason: turning it on for every existing target would
-- stop every automation that has ever called these endpoints, and it would arrive by surprise.
ALTER TABLE connection_profile ADD COLUMN requires_approval BOOLEAN NOT NULL DEFAULT FALSE;
