-- Phase 11: somewhere backups can be sent.
--
-- A destination is a thing rather than a string on each schedule: otherwise every backup job
-- carries its own copy of a bucket name and a secret key, and rotating the key means editing
-- all of them.
--
-- The columns are deliberately generic. A bucket and a host are both "where"; a key prefix
-- and a remote directory are both "which part of it". A table with bucket and host beside
-- each other would have one of them null in every row.

CREATE TABLE backup_destination
(
    id          BIGINT       NOT NULL,
    name        VARCHAR(200) NOT NULL,
    kind        VARCHAR(16)  NOT NULL,
    enabled     BOOLEAN      NOT NULL,
    location    VARCHAR(300),
    path        VARCHAR(500),
    port_number INTEGER,
    endpoint_url VARCHAR(500),
    region      VARCHAR(64),
    path_style  BOOLEAN      NOT NULL,
    access_key  VARCHAR(300),
    -- Encrypted at rest by EncryptedStringConverter, which is why these are far wider than
    -- the secrets they hold.
    secret_key  VARCHAR(2000),
    private_key VARCHAR(8000),
    passphrase  VARCHAR(2000),
    use_tls     BOOLEAN      NOT NULL,
    CONSTRAINT backup_destination_pkey PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_backup_destination_name ON backup_destination (name);

-- Increment 50 to match Hibernate's default allocation size, as elsewhere in this schema.
CREATE SEQUENCE backup_destination_seq START WITH 1 INCREMENT BY 50;

-- A schedule now names where its backup goes, and how many of them to keep. Both live in the
-- job's own settings JSON, so nothing here changes: the column that holds them already exists.
