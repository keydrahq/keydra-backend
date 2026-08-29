-- Phase 14: a backup the server itself cannot read.
--
-- The passphrase of phase 13 is stored on the server, encrypted with the server's key. That is
-- a real improvement over nothing and it has an honest limit: whoever can read the database and
-- the configuration can read the backups. A public key changes what is being claimed — Keydra
-- holds only the half that encrypts, so an instance that writes a backup every night cannot
-- read one back, and neither can whoever takes the machine.
--
-- Not stored like a secret, because it is not one: this is the half that only encrypts, and
-- showing it is how somebody checks the right key is configured.

ALTER TABLE backup_destination ADD COLUMN recipient_public_key VARCHAR(200);
