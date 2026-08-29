-- The certificates a target's TLS actually needs, per target.
--
-- Until now a profile carried a TLS flag and the client trusted the JVM's own store, which holds
-- the public authorities. That is right for a managed target and useless for most of the Redis
-- inside a company: a certificate signed by an authority that exists only there. The only way to
-- reach one was to put that authority into the JVM's trust store — a deployment-wide change made
-- outside Keydra for one target, and one that cannot be undone for one target either.
ALTER TABLE connection_profile ADD COLUMN tls_ca_cert TEXT;
ALTER TABLE connection_profile ADD COLUMN tls_client_cert TEXT;

-- The private half, and the only one of the three that is a secret. Encrypted at rest with the
-- same key every other stored credential uses, never returned by the API, never logged — the same
-- treatment ssh_tunnel.private_key gets, because it is the same kind of thing.
ALTER TABLE connection_profile ADD COLUMN tls_client_key TEXT;
