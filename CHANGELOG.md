# Changelog

Notable changes to keydra-backend. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) — with the caveat that below 1.0.0
a minor bump may break an API.

## [Unreleased]

Nothing yet.

## [0.0.1] — 2026-08-29

The first tagged version, and a pre-release: below 1.0.0, and not yet carried by anybody
else's deployment.

### What this version does

**Targets** — connection profiles for standalone, cluster and Sentinel RESP servers, with TLS
(including a private authority to trust and a client certificate to present), per-database
selection, and SSH tunnels described once and pointed at from anywhere. Aerospike and TiKV
through an engine each.

**Data** — a streaming key browser over `SCAN`, a namespace tree, an editor per value type, a
decoder chain, key operations, and import and export through the server's own `DUMP` and
`RESTORE`.

**Operating** — a command console whose deny-list has two halves and refuses each for its own
reason, a live command watch, Pub/Sub, a monitoring dashboard, keyspace analysis, and cluster
and Sentinel topology.

**Automation** — key migration between two targets, scheduled work, alert rules with
historical baselines and five kinds of delivery, and backups to seven kinds of destination
with retention and encryption.

**Security** — a grant model of subjects, scopes, roles and permissions over two acyclic
graphs; local accounts with Argon2id; identity providers configured while running;
invitations and password resets; sessions that can be listed and ended; a second factor an
account can pair and an installation can require; targets that can be made to ask for their
own name or for a second person before they are emptied; a full audit log; and an instance
key that can be rotated without downtime.

**Running it** — one container image, more than one instance against one database with a
database-held lease, an optional shared store, instances that can be drained before they are
stopped, Prometheus metrics, OpenTelemetry traces and JSON logs.

### Known limitations

Recorded because they are asked about, not because they are hidden.

- **LDAP is not supported.** It is a bind and a search rather than a browser redirect, and the
  provider work is built on never seeing a password that is not Keydra's own.
- **An alert rule is about one target.** It can announce itself in several places, but what it
  compares is one target's readings.
- **Only reachability alerts on Keydra itself.** An identity provider or a backup destination
  that stops answering announces itself; an instance that stops beating is readable on the
  instances page and does not.
- **A second factor can be required of local accounts only.** An account that signs in through
  a provider proved who it was there, and whether that provider asked for a second factor is
  not Keydra's to know or repeat.
- **Backing up Keydra's own database is the deployment's job.** It is an ordinary PostgreSQL.

[Unreleased]: https://github.com/keydrahq/keydra-backend/compare/v0.0.1...HEAD
[0.0.1]: https://github.com/keydrahq/keydra-backend/releases/tag/v0.0.1
