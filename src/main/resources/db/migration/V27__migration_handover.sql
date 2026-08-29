-- What a migration needs so somebody else can finish it.
--
-- A migration lived only in the memory of the instance walking the keyspace. Its row said what was
-- being moved and how far it had got, but not what it had been asked to do — the prefixes, the
-- script, whether the source keys were being deleted — so no other instance could have carried it
-- on even if it had noticed it was abandoned. And nothing noticed: a row under the name of an
-- instance that never came back sat at RUNNING for ever.
--
-- Three columns. The request, so a second instance knows what the job was. When its owner last
-- said it was still working, so "abandoned" is a question that can be answered. And how many times
-- it has changed hands, because a job quietly taken over twice is a job worth looking at.

ALTER TABLE key_migration ADD COLUMN request TEXT;
ALTER TABLE key_migration ADD COLUMN claimed_at TIMESTAMP;
ALTER TABLE key_migration ADD COLUMN resumed INTEGER NOT NULL DEFAULT 0;

-- Rows written before this existed are claimed as of now rather than left looking abandoned: an
-- upgrade must not hand every running migration to another instance the moment it starts.
UPDATE key_migration SET claimed_at = now() WHERE state = 'RUNNING';

-- The sweep asks for running rows and nothing else, which on a table that is mostly history is a
-- question worth answering from an index.
CREATE INDEX idx_key_migration_claim ON key_migration (state, claimed_at);

-- Which instance ran a scheduled job.
--
-- The scheduler only runs on whichever instance holds the chores, so a run row that says RUNNING
-- after that instance is gone is a run nobody will ever finish or record. Without a name on the
-- row there is no way to tell that from a job that is still going on an instance that is still
-- here.
ALTER TABLE job_run ADD COLUMN instance_id VARCHAR(64);
