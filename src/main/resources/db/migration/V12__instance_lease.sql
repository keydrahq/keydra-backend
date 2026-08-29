-- More than one Keydra against one database.
--
-- Everything since the scheduler arrived assumed there was one of these running. Two instances
-- behind a load balancer — which is how anything gets upgraded without going down — and the
-- schedules run twice, the alerts fire twice, and every watched target is sampled by two
-- processes that each believe they are the only one. None of that announces itself: the second
-- copy of a nightly flush looks exactly like the first.
--
-- One row per kind of work that must happen once, held for a few seconds at a time and renewed.
-- An instance that stops renewing loses it to whoever asks next, which is what makes a crash
-- recoverable without anybody deciding anything.
--
-- The expiry is written by the database's clock rather than by any instance's, and that is the
-- whole trick: two machines whose clocks differ by a minute still agree about whose lease has
-- run out, because neither of them is the one being asked.

CREATE TABLE instance_lease
(
    role       VARCHAR(64) NOT NULL,
    holder     VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT instance_lease_pkey PRIMARY KEY (role)
);

-- Which instance was running a migration, so a restart sweeps up after itself and not after
-- the other one. Without this, an instance starting up marks every RUNNING row as interrupted
-- — including the ones another instance is at that moment still working through.
ALTER TABLE key_migration ADD COLUMN instance_id VARCHAR(64);
