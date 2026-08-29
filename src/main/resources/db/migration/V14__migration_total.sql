-- How many keys a migration expected to move.
--
-- Nullable, and it stays null for a migration taken with a glob: nothing can say how many keys
-- match a pattern without walking the keyspace, which is the job itself. Where it is known — the
-- caller named the keys, or the whole database is moving and the store counted it — a progress bar
-- has a denominator that does not move, instead of one that grows in step with the numerator and
-- therefore reads as finished from the first batch.
ALTER TABLE key_migration ADD COLUMN total_keys BIGINT;
