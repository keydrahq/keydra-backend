-- Phase 12: a jump host becomes a thing rather than six fields on whatever needed it.
--
-- The columns on connection_profile were right for one target and wrong for twenty behind the
-- same bastion: twenty copies of one key, and rotating it means editing twenty profiles and
-- missing one. They were also unreachable by anything else — a backup destination behind the
-- same bastion could not use the tunnel that already existed.

CREATE TABLE ssh_tunnel
(
    id                   BIGINT       NOT NULL,
    name                 VARCHAR(200) NOT NULL,
    host                 VARCHAR(255) NOT NULL,
    port_number          INTEGER      NOT NULL,
    username             VARCHAR(200) NOT NULL,
    -- Encrypted at rest by EncryptedStringConverter, which is why these are far wider than
    -- the secrets they hold.
    password             VARCHAR(2048),
    private_key          VARCHAR(8192),
    passphrase           VARCHAR(2048),
    -- SHA256:… of the key the bastion is expected to present. Null accepts any, which is what
    -- the profile columns did and why this exists.
    host_key_fingerprint VARCHAR(128),
    CONSTRAINT ssh_tunnel_pkey PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_ssh_tunnel_name ON ssh_tunnel (name);

-- Increment 50 to match Hibernate's default allocation size, as elsewhere in this schema.
CREATE SEQUENCE ssh_tunnel_seq START WITH 1 INCREMENT BY 50;

ALTER TABLE connection_profile ADD COLUMN tunnel_id BIGINT;
ALTER TABLE connection_profile
    ADD CONSTRAINT connection_profile_tunnel_fk FOREIGN KEY (tunnel_id)
        REFERENCES ssh_tunnel (id) ON DELETE SET NULL;
CREATE INDEX idx_connection_profile_tunnel ON connection_profile (tunnel_id);

ALTER TABLE backup_destination ADD COLUMN tunnel_id BIGINT;
ALTER TABLE backup_destination
    ADD CONSTRAINT backup_destination_tunnel_fk FOREIGN KEY (tunnel_id)
        REFERENCES ssh_tunnel (id) ON DELETE SET NULL;

-- What is already configured moves into rows. The credentials are copied as they are: they are
-- ciphertext produced by the same converter with the same key, so nothing has to be decrypted
-- here and nobody has to re-enter a bastion password to keep their targets reachable.
--
-- One row per distinct (host, port, username), because that is what "the same bastion" means.
-- The name is the host, with the user in front when two accounts share one.
INSERT INTO ssh_tunnel (id, name, host, port_number, username, password, private_key, passphrase)
SELECT nextval('ssh_tunnel_seq'),
       CASE WHEN count(*) OVER (PARTITION BY tunnel_host) > 1
            THEN tunnel_username || '@' || tunnel_host
            ELSE tunnel_host END,
       tunnel_host,
       tunnel_port,
       tunnel_username,
       min(tunnel_password),
       min(tunnel_private_key),
       min(tunnel_passphrase)
FROM connection_profile
WHERE tunnel_enabled = true
  AND tunnel_host IS NOT NULL
  AND tunnel_username IS NOT NULL
GROUP BY tunnel_host, tunnel_port, tunnel_username;

UPDATE connection_profile p
SET tunnel_id = t.id
FROM ssh_tunnel t
WHERE p.tunnel_enabled = true
  AND p.tunnel_host = t.host
  AND p.tunnel_port = t.port_number
  AND p.tunnel_username = t.username;

ALTER TABLE connection_profile DROP COLUMN tunnel_enabled;
ALTER TABLE connection_profile DROP COLUMN tunnel_host;
ALTER TABLE connection_profile DROP COLUMN tunnel_port;
ALTER TABLE connection_profile DROP COLUMN tunnel_username;
ALTER TABLE connection_profile DROP COLUMN tunnel_password;
ALTER TABLE connection_profile DROP COLUMN tunnel_private_key;
ALTER TABLE connection_profile DROP COLUMN tunnel_passphrase;
