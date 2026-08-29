# Contributing to keydra-backend

Thank you for taking the time. This file is what a pull request is read against, so it is
short and specific rather than encouraging.

## Getting set up

```bash
podman play kube deploy/keydra-dev.yaml   # targets, plus Keydra's own PostgreSQL
./mvnw quarkus:dev
./mvnw verify                             # tests and the format check
```

Java 21, and the repository's own `./mvnw` — never a system Maven. A container engine has to
be running: the tests start PostgreSQL, Redis and Valkey through Testcontainers.

## The rules a review will hold you to

These are not style preferences. Each one is here because breaking it produced a bug that
took a while to find.

**Nothing blocks an event loop.** REST methods return `Uni`/`Multi`, persistence is Hibernate
Reactive Panache (`@WithSession` / `@WithTransaction`), and target I/O is Vert.x based. No
`@Transactional`, no JDBC, no `.await()` in application code. Mixing the two models is what
produces `BlockingOperationNotAllowedException`, and it does so under load rather than in a
test — which is why the rule is all-reactive rather than case-by-case `@Blocking`.

**Never `KEYS`.** Key enumeration is always a cursor-based `SCAN` with server-side pagination.

**All target I/O goes through the engine.** `io.keydra.engine.KeyValueEngine`, reached through
`ConnectionRegistry`. No ad-hoc clients in resources or services. A capability a store may not
have is `Optional` on the engine — `console()`, `messaging()` — never a method that throws at
the first call.

**A broadcast names the target it is about, or it goes to everybody.** `NotificationHub`
filters by `Notification.connectionId`, so a viewer with a grant on one target hears nothing
about any other. Use `broadcast(category, connectionId, payload)` for news about one target
and the two-argument form only for news about Keydra itself. Getting this wrong in the safe
direction is a page that refreshes a moment late; in the other it is a disclosure.

**Anything fetched from an address somebody typed goes through `common.net.EgressGuard`** —
webhooks, issuer discovery, object-store endpoints. Check when the address is stored *and*
again before the request, because a row can predate the check.

**Work off a request re-checks the actor it stored.** A schedule firing or an approved
operation running has no ambient `SecurityIdentity`, so asking for one silently gets nothing.
Use the domain's guard bean and `AuditService.recordAs`.

**Secrets never reach a log and are never returned by the API.** Not a target password, not a
token, not a passphrase.

**One package per responsibility.** An entity, a DTO, an exception and a resource never share
a package. `RulesDescribeTheCodeTest` checks the domain list against the tree.

**Mapping is generated.** MapStruct in a `mapper` package, with `unmappedTargetPolicy=ERROR`
so a new field nobody mapped fails the build instead of arriving as null. Only rules that are
not plain copies are written by hand, in `@AfterMapping`.

**Every GraphQL operation carries a guard.** `@RolesAllowed` plus whatever the REST equivalent
requires. `GraphQLCoverageTest` fails the build for an operation with none.

## Tests

Every feature ships with tests. JUnit 5 and REST Assured; integration tests use Testcontainers
against both `redis:8-alpine` and `valkey/valkey:9-alpine`, because "it works on Redis" has
not been the same statement as "it works" since Valkey forked.

A test that asserts on a sentence the interface shows is a test that will fail when somebody
translates it. Assert on the fact — a count, a status, an id — and let the interface write the
sentence.

## Style

google-java-format, enforced by Spotless and bound to `validate`. `./mvnw spotless:apply`
fixes drift; the build fails on it rather than warning.

Comments explain *why*, not *what*. A comment restating the line above it is noise; a comment
saying which of two plausible designs this is and what the other one cost is the reason the
file is readable a year later.

No `TODO` without a linked issue.

## Commits and pull requests

Small and reviewable. One concern per pull request — a rename and a behaviour change in the
same diff means neither gets read properly.

Write the commit message for somebody bisecting six months from now: what changed, and what
would have gone wrong without it.

Before opening a pull request:

```bash
./mvnw verify
```

## Reporting a security problem

Do not open an issue. See [SECURITY.md](SECURITY.md).

## Licence

By contributing, you agree that your contributions are licensed under the Apache License 2.0,
the same terms that cover the rest of this repository.
