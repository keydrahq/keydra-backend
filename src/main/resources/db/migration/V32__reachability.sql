-- The last time something outside Keydra was asked whether it was there.
--
-- The status page counted what an installation reaches and could not say whether any of it answered
-- — which is a page reporting numbers somebody typed in themselves. Probing on page load was
-- refused in phase 40 and the refusal was right: ten people watching would be ten times the load of
-- one, aimed at somebody else's service.
--
-- So the asking happens on one instance on a slow clock and the answer is written here, and the
-- page reads a row rather than causing a request.
--
-- The last answer only. A history of reachability is a different page and one whose rows are worth
-- nothing an hour later.
CREATE TABLE reachability_check (
    -- What kind of thing this is, matching common.reach.Reachable.kind().
    kind       VARCHAR(64)  NOT NULL,
    -- Its id within that kind. No foreign key: the kinds live in different tables, and a row about
    -- something since deleted is a row that ages out on the next walk rather than a cascade.
    subject_id BIGINT       NOT NULL,
    checked_at TIMESTAMPTZ  NOT NULL,
    ok         BOOLEAN      NOT NULL,
    -- What it said, or why it did not. A sentence, never a credential.
    detail     VARCHAR(500),
    PRIMARY KEY (kind, subject_id)
);
