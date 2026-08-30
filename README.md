# keydra-backend

[![CI](https://github.com/keydrahq/keydra-backend/actions/workflows/ci.yml/badge.svg)](https://github.com/keydrahq/keydra-backend/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://adoptium.net/)

The Quarkus backend of [Keydra](https://github.com/keydrahq), a web-based management console
for key-value servers — **Redis**, **Valkey**, **KeyDB**, **Dragonfly**, **Garnet**,
**Aerospike** and **TiKV**. Multi-user and deployable, rather than a desktop tool on one
person's laptop.

This repository is the API and everything behind it. The interface lives in
[keydra-frontend](https://github.com/keydrahq/keydra-frontend) and talks to it only over the
REST/WebSocket API; the manual lives in [keydra-doc](https://github.com/keydrahq/keydra-doc).

## What it does

Reads and writes somebody else's key-value servers on behalf of several people, and decides
which of them may do what.

- **One engine per protocol.** The five RESP servers share one implementation — the flavour
  is read from what a server says about itself rather than from a family tree. Aerospike and
  TiKV speak nothing of the sort and have an engine each. What a target can do is asked, not
  assumed: an Aerospike target arrives with three tabs where a Redis target has eleven.
- **Never `KEYS`.** Key enumeration is always a cursor-based `SCAN`, streamed as it goes, so
  the size of a keyspace is not a limit.
- **Non-blocking end to end.** REST methods return `Uni`/`Multi`, persistence is Hibernate
  Reactive Panache, and target I/O is Vert.x based. There is no blocking call on an event
  loop, which is what lets one process stream a million-key scan to several browsers.
- **Two API surfaces, one set of rules.** REST under `/api/v1` and GraphQL at `/graphql` call
  the same services and carry the same permission checks.
- **Credentials encrypted at rest.** AES-256-GCM under an instance key that can be rotated
  without downtime; account passwords are Argon2id. Nothing secret is ever returned by the
  API or written to a log.

TiKV is the one engine the published image leaves out. Its client bundles copies of netty,
jackson, guava and protobuf that nothing can upgrade — forty-nine advisories an installation
managing no TiKV was carrying for nothing — so it is built with the `tikv` Maven profile
instead. A build without it refuses a TiKV target while you are still filling in the form.

## Requirements

- **Java 21.** Do not use a system Maven — the repository ships `./mvnw`.
- **PostgreSQL.** Keydra keeps its own data here and there is no alternative: the application
  is non-blocking and uses a reactive driver, and there is no reactive H2.
- **[Podman](https://podman.io/)** (or Docker) to run the local targets and the tests, which
  start containers through Testcontainers.

## Running it

Two containers are enough to start: the database Keydra keeps its own data in, and one target
to point it at.

```bash
# Keydra's own store. Ports are shifted off the defaults so this starts on a machine
# that already runs a PostgreSQL or a Redis.
podman run -d --name keydra-db -p 5442:5432 \
  -e POSTGRES_DB=keydra -e POSTGRES_USER=keydra -e POSTGRES_PASSWORD=keydra \
  docker.io/library/postgres:17-alpine

# Something to manage.
podman run -d --name keydra-target -p 6479:6379 docker.io/library/redis:8-alpine

./mvnw quarkus:dev
#    API      http://localhost:8181/api/v1/about
#    health   http://localhost:8181/q/health
#    OpenAPI  http://localhost:8181/api/openapi
#    Swagger  http://localhost:8181/q/swagger-ui   (dev profile only)
```

The `%dev` profile already points at those two, so nothing needs configuring to get a running
instance. It also seeds an `admin` / `admin` account on a database with no accounts, so
enforcement being on in development does not mean clicking through first-run setup after every
fresh database. Only the dev profile sets that, and there is no value for a production
instance to fall back to.

In production an instance with no accounts offers to make the first administrator instead of
asking you to sign in, and the endpoint that does it closes the moment any account exists.

Add a connection on `localhost:6479`, leave the server set to ask the target, and it works the
flavour out from what the server answers.

> The full development pod — the seven kinds of target, the shared store and ClickHouse —
> lives in the deployment manifests rather than here, because it is not this repository's to
> describe.

## Configuration

Quarkus configuration: properties in `src/main/resources/application.properties`, with
environment variables overriding them. Only four things have no useful default.

```properties
KEYDRA_DB_URL=postgresql://db.internal:5432/keydra
KEYDRA_DB_USERNAME=keydra
KEYDRA_DB_PASSWORD=<the password>
# 32 random bytes, base64. Losing it loses every stored credential.
KEYDRA_SECRET_KEY=<openssl rand -base64 32>
```

Everything else has a default that is either correct or explicitly a development
convenience. Before exposing an instance, read what
[the manual](https://github.com/keydrahq/keydra-doc) says about
`KEYDRA_PUBLIC_URL`, `KEYDRA_BEHIND_PROXY` and `KEYDRA_TRUSTED_PROXIES`.

> **`KEYDRA_SECURITY_ENABLED=false` admits everybody who can reach the address, with every
> permission.** It exists for a demonstration or a machine only you can reach. Every page
> says "security off" while it is set, because an open instance that looks secured is how one
> ends up exposed.

## Tests

```bash
./mvnw verify        # JUnit 5 + REST Assured, plus the Spotless format check
```

Tests start PostgreSQL, Redis and Valkey through Testcontainers, so a container engine has to
be running. Format drift fails the build: Spotless is bound to `validate`, and google-java-format
is not a suggestion.

## How the code is arranged

One package per domain, and within each domain one package per responsibility. An entity, a
DTO, an exception and a resource never share a package, and no class takes on more than one of
those jobs.

| sub-package | holds |
|---|---|
| `rest` | JAX-RS resources and their exception mappers — transport only |
| `graphql` | resolvers, calling the same services the resources call |
| `ws` | WebSocket endpoints |
| `service` | business rules and orchestration |
| `mapper` | entity↔DTO translation, generated with MapStruct |
| `registry` | long-lived client and connection lifecycle |
| `entity` | JPA entities and their enums |
| `persistence` | converters, repositories, persistence-only helpers |
| `dto` | request and response records crossing the API |
| `exception` | domain exceptions, mapped to HTTP in that domain's `rest` |

Dependency direction is one way: `rest`/`graphql`/`ws` → `service` → `registry`/`persistence`
→ `entity`/`dto`. A test checks that list against the tree, because a file saying where things
go is worse than no file when it is wrong.

Every backing store sits behind `io.keydra.engine.KeyValueEngine`. Protocol types never cross
it: services above deal in `KeyEntry` and `ServerInfo`. Adding a store means adding an
`EngineType` and an implementation, not touching the services.

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) first — it covers the rules a pull request is read
against, most of which are about not blocking an event loop and not inventing a second way to
say the same thing. Security reports go through [SECURITY.md](SECURITY.md) rather than the
issue tracker.

## Licence

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
