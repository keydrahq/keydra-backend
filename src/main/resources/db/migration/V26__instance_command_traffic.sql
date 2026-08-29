-- How many commands this instance has sent to the servers it watches.
--
-- The main thing Keydra does, and the number that was nowhere: the metrics page shows what a
-- target is doing, which is a different question from what this Keydra is asking of it.
--
-- Cumulative, like the bus counters beside it, for the same reason: two readings make a rate and
-- one reading is a number nothing can disagree about.
ALTER TABLE keydra_instance ADD COLUMN commands BIGINT NOT NULL DEFAULT 0;
