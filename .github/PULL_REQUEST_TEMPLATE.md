<!--
  One concern per pull request. A rename and a behaviour change in the same diff
  means neither gets read properly.
-->

## What this changes

<!-- What it does, and what would have gone wrong without it. -->

## Why

<!--
  The reason, not the restatement. If you chose between two designs, say which one this
  is and what the other one cost — that is the part nobody can reconstruct later.
-->

Fixes #

## How it was checked

<!-- What you ran, and what you looked at. `./mvnw verify` is the floor, not the answer. -->

- [ ] `./mvnw verify` passes
- [ ] New behaviour has tests
- [ ] Tested against more than one store, where the change is about a store

## The rules this touches

<!-- Tick what applies, and say a word about it below if the answer is interesting. -->

- [ ] Nothing added blocks an event loop — no `@Transactional`, no JDBC, no `.await()`
- [ ] Key enumeration still uses `SCAN`, never `KEYS`
- [ ] Target I/O goes through `KeyValueEngine`, not an ad-hoc client
- [ ] Any new broadcast names the target it is about
- [ ] Any new outbound address goes through `EgressGuard`
- [ ] Any new GraphQL operation carries `@RolesAllowed` and its permission check
- [ ] No secret can reach a log, a metric label or an API response
- [ ] Work that runs off a request re-checks the actor it stored

## Anything a reviewer should look at first

<!-- The part you are least sure about. Saying so gets you a better review, not a worse one. -->
