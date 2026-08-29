-- One row per running Keydra.
--
-- The lease table answers "who does the chores"; this answers "who is here", which is a different
-- question and the one nobody could ask. A rolling upgrade has one leader and two instances.
CREATE TABLE keydra_instance (
    id            VARCHAR(64) PRIMARY KEY,
    version       VARCHAR(64) NOT NULL,
    commit        VARCHAR(64),
    started_at    TIMESTAMPTZ NOT NULL,
    -- Written by the database's clock, so two machines whose own clocks differ still agree about
    -- which of them was heard from more recently.
    last_seen_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_keydra_instance_seen ON keydra_instance (last_seen_at);
