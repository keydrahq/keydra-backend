-- Phase 13.2: a backup the place it lands cannot read.
--
-- Phase 11 sent the values somewhere else, which is what a backup is for, and what leaves is
-- every key and every value in a bucket somebody else administers. "The bucket is private" is
-- a sentence about access control rather than about what is in the file.
--
-- On the destination rather than on each job, because it is a property of the place: two
-- schedules writing to the same bucket must produce files that can be read the same way.

ALTER TABLE backup_destination ADD COLUMN encryption_passphrase VARCHAR(2048);

COMMENT ON COLUMN backup_destination.encryption_passphrase IS
    'Encrypted at rest like every other secret. Lose it and the backups written with it are'
    ' unreadable — by anybody, Keydra included, which is the point.';
