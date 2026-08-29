-- The keys a destination's backups can be opened with, which is now a list.
--
-- One recipient meant the person holding that private half was the only person in the world who
-- could read a year of backups. They leave, they lose the password manager, they are on a plane on
-- the morning the cache is empty — and "encrypted at rest" becomes "gone", which is the failure the
-- encryption exists to prevent, arrived at from the other side.
--
-- A label because a column of keydra-pk1:... is a list nobody can act on: removing one means
-- knowing which one, and the only thing distinguishing them is forty characters of base64.
CREATE TABLE backup_recipient (
    id             BIGSERIAL    PRIMARY KEY,
    destination_id BIGINT       NOT NULL REFERENCES backup_destination (id) ON DELETE CASCADE,
    -- What somebody calls this key: "Ada's key", "the safe", "offsite".
    label          VARCHAR(200) NOT NULL,
    -- The public half. Not a secret and deliberately not stored like one — it is the half that
    -- only encrypts, and showing it is how somebody checks the right key is configured.
    public_key     VARCHAR(200) NOT NULL,
    added_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_backup_recipient_destination ON backup_recipient (destination_id);

-- The key each destination already has becomes the first row of its own list, named for the
-- destination it came from. A migration that left the label empty would be a list of one blank
-- name on the morning somebody upgraded.
INSERT INTO backup_recipient (destination_id, label, public_key, added_at)
SELECT id, name, recipient_public_key, now()
  FROM backup_destination
 WHERE recipient_public_key IS NOT NULL
   AND recipient_public_key <> '';

ALTER TABLE backup_destination DROP COLUMN recipient_public_key;
