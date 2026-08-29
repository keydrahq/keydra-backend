# Authorization

How Keydra decides who may see which server and do what to it.

This document is the design, and it is what
`RulesDescribeTheCodeTest` checks the `Permission` enum against: a permission the code defines
and this file does not explain is a permission nobody can be told the meaning of.

## What is wrong with what exists

Today Keydra has three roles — viewer, operator, admin — read from a claim in an OIDC token,
and they apply to the whole instance. That was the right size for one team running its own
servers. It cannot express the thing every deployment past the first one needs:

> The payments team may read and write the payments cache, may read the sessions cache, and
> must not know the analytics cluster exists.

Three global roles cannot say that, because they say nothing about *which* server. Everything
below follows from making the subject, the thing, and the verb three separate ideas.

## The model

Four nouns, and one sentence that joins them.

| Noun | What it is |
|---|---|
| **Subject** | A user, or a group. Groups may contain users and other groups. |
| **Scope** | What a grant is about: the whole instance, one server group, or one connection. |
| **Role** | A named bundle of permissions. Built-in ones cannot be edited; custom ones can. |
| **Permission** | One thing somebody may do, from a closed list. |

> **A grant says: this *subject* holds this *role* on this *scope*.**

Everything else is derived. What a user may do to a connection is the union of the permissions
of every role granted to them, or to any group containing them, on that connection or on
anything containing it.

### Why a graph rather than a table

Groups contain groups, and scopes contain scopes. Both are directed acyclic graphs, and
resolution is a walk over them:

```
subjects                     scopes
  alice ──▶ payments-devs      instance
              │                   │
              ▼                   ├──▶ payments-servers ──▶ payments-cache
            engineers             │                    └──▶ payments-sessions
                                  └──▶ analytics-servers ──▶ analytics-cluster
```

A grant of `operator` to `engineers` on `payments-servers` reaches Alice through the subject
graph and reaches `payments-cache` through the scope graph. Neither edge is written twice, which
is the point: an organisation that has to restate its structure in a permission table will stop
maintaining the table.

Both graphs are kept acyclic on write. A group that contained itself would make resolution
non-terminating, and there is no useful meaning to give it.

### Grants only, never denials

There are no "deny" rules. A permission system with both is one where the answer to "why can
Alice not see this?" is a search rather than a lookup, and the answer to "is this locked down?"
is nobody's to give.

If somebody should not reach a server, they are not granted anything on it. Absence is the
denial, and absence is visible: the grants page shows what is there, and what is there is all
there is.

### Visibility is not a separate feature

A connection appears to a user when they hold **any** permission on it. There is no "can see"
flag to keep in step with the permissions — a target somebody may do nothing with is a target
they have no reason to know about, and the two facts cannot drift apart because there is only
one of them.

This is what answers "her kullanıcı her sunucuyu göremez": a user with no grant touching a
connection does not see it in the catalog, in the overview, in the fleet totals, or in a
migration's target list.

## The permissions

A closed list, because an open one cannot be enforced: an endpoint has to name the permission it
requires, and a permission nothing requires protects nothing.

### On a connection

| Permission | Lets somebody |
|---|---|
| `connection:view` | See that the target exists, and its status |
| `connection:edit` | Change the profile — where it points, its credentials |
| `connection:delete` | Remove the profile |
| `keys:read` | Browse and search the keyspace |
| `keys:write` | Create, rename, copy keys and change expiries |
| `keys:delete` | Delete keys, namespaces, or everything |
| `values:read` | Open a key's value |
| `values:write` | Change a value |
| `console:run` | Run commands, subject to the deny-list |
| `pubsub:subscribe` | Listen to channels |
| `pubsub:publish` | Publish messages |
| `commands:watch` | Watch every command the server runs |
| `monitoring:read` | The dashboard, slow log, client list |
| `monitoring:manage` | Start and stop sampling, kill clients |
| `analysis:read` | The keyspace report |
| `server:read` | The server's settings and persistence state |
| `server:configure` | Change settings, snapshot, rewrite the log |
| `acl:read` | The server's own ACL users |
| `acl:manage` | Create and change them |
| `migration:run` | Move keys out of this target |
| `transfer:export` / `transfer:import` | Take keys out as a file, or put them back |
| `schedule:manage` | Arrange work to happen to this target later. Required *as well as* what the work itself needs: a schedule is a way of doing something later, not a way of doing something you may not do |
| `alert:manage` | Write the rules that watch this target and say where they send. Beside `monitoring:manage` rather than inside it, because a rule outlives the person who wrote it |

### On the instance

| Permission | Lets somebody |
|---|---|
| `connection:create` | Add a target at all |
| `groups:manage` | Create server groups and move connections between them |
| `users:manage` | Create users, put them in groups |
| `grants:manage` | Grant and revoke roles |
| `idp:manage` | Configure identity providers |
| `audit:read` | Read the audit log |
| `policy:manage` | Decide what this installation asks of everybody who signs in — a second factor, and the terms. Its own permission rather than part of `users:manage`, because making an account and restricting every account are different acts with different blast radii |
| `script:run` | Run a script inside Keydra. On the instance and not on a target, which is the point of it: a script runs in the process that holds every target's credentials and can reach every network this host can |
| `backup:manage` | Decide where backups go. A destination carries credentials to somewhere outside, and somebody who may back one server up is not thereby somebody who decides backups leave for a bucket of their choosing |
| `tunnel:manage` | Describe the jump hosts. A jump host carries a credential that reaches a whole network, and everything Keydra holds for everything behind it travels through it |
| `alert-delivery:manage` | Decide where alerts are sent, and where Keydra's own troubles are announced. It holds a token to somewhere outside, and choosing where a server's troubles are announced is not part of watching that server |
| `crypto:rotate` | Move every stored credential onto a new key. The most consequential thing this application can be asked to do, and the only permission that is about the secrets rather than about what they unlock |
| `instance:read` | Read how Keydra itself is doing: which instances are running, and what they rest on. Not open — a roster names hosts, and a map of the installation is worth more to somebody who should not have one |
| `instance:drain` | Take an instance out of service and put it back. Separate from reading the roster, because reading where the work is and deciding where it goes are not the same act |

Instance permissions are granted on the instance scope and nowhere else. `users:manage` scoped
to one server group would mean nothing, and a model that lets it be written is a model that
has to explain what it did.

### Built-in roles

Three, matching what exists today so that nothing regresses when the model lands:

- **viewer** — every `*:read`, plus `connection:view`, `pubsub:subscribe`
- **operator** — viewer, plus every write on data: `keys:*`, `values:*`, `console:run`,
  `pubsub:publish`, `commands:watch`, `migration:run`, `transfer:*`, `monitoring:manage`
- **admin** — everything, including the instance permissions

They cannot be edited. A deployment that needs something between them makes a custom role; a
deployment that redefines `operator` makes every other deployment's documentation wrong.

## Identity

Who somebody is, and who says so, are separate from what they may do. The model above takes a
subject; this section is about where subjects come from.

### Several providers, configured while running

Providers are rows in a table, not properties in a file. Adding a way into Keydra should not be a
redeploy, and the deployments that need a second one need it on a Tuesday afternoon.

One implementation serves them all, because there is only one flow: send somebody to an
authorization endpoint, take the code that comes back, swap it for a token at a token endpoint,
and ask who they are. What differs between Keycloak and GitHub is which URLs those are and what
the answer's fields are called. An OIDC provider publishes a discovery document and its endpoints
are fetched from it; an OAuth 2 provider publishes nothing and has them typed in.

Discovery happens when a provider is saved rather than when somebody signs in. Saving is where
somebody is waiting for an answer and able to act on it; a sign-in is not, and a discovery
document fetched at every sign-in makes every sign-in depend on somebody else's slowest morning.
The endpoints Keydra ends up with are shown on the provider's row, because they are the first
thing to look at when a sign-in does not work.

Three things protect the flow, and each of them is protecting against something specific:

- **A state value** invented here, kept in a cookie only this application can read, and required
  to come back unchanged. Without it, anybody could send somebody's browser to the callback with
  a code of their own and have them signed in as an account they do not own.
- **PKCE**, on every flow including the confidential ones that do not strictly need it. A code
  intercepted between the provider and Keydra is worthless without the verifier, and the cost of
  always sending one is a hash.
- **The back channel.** The code is exchanged by Keydra, directly with the provider, over TLS.
  That is also why an id token read on this path is not signature-checked: it arrived in the
  answer to a request this application made to that endpoint, so the connection establishes where
  it came from and there is no untrusted party in between. OIDC Core says as much in §3.1.3.7. A
  token arriving any other way would need its signature checked, and none does.

The claims come from the provider's user endpoint wherever there is one, even for OIDC where an
id token would do — so the claim mapping is one thing to configure rather than one per kind.

Which claim is which is configuration, and the one that matters most is the subject: not the
username, because usernames change and an account matched by one would become a second account
the day somebody married.

### Groups from a directory

A provider can name the groups somebody is in, and a mapping says which Keydra group each of its
names means. That is what makes an existing directory worth having: a deployment states its
structure once, where it already knows it, and Keydra's grants point at groups that fill
themselves.

The mapping is also the boundary of what the provider owns. At every sign-in, membership of the
mapped groups is replaced by what the claim says — so removing somebody from a directory group
removes their access here — while groups nobody mapped are left exactly as they are, because an
administrator put somebody there by hand for a reason the directory has no opinion about.

### Login

A page that lists the configured providers as buttons, plus a username and password form when
local accounts are enabled. One provider and no local accounts means the page redirects rather
than asking anybody to click through a single choice.

An instance with enforcement on and no accounts at all shows neither: it offers to create its
first administrator. A login form on an instance nobody can log into is a wall with no key, and
the usual answer to "I have no account" — ask an administrator — has nobody to ask yet. The
endpoint behind it is open, and is safe for exactly one reason: it refuses the moment any account
exists, so it can be reached once, on an instance nobody can yet sign into.

The session is a signed, encrypted cookie carrying a name and an expiry. Every request rebuilds
the roles and permissions from the grants as they stand, so revoking somebody's access takes
effect on their next request rather than at their next sign-in. The corollary is that signing out
clears the cookie rather than revoking it: a copy taken from a browser beforehand keeps working
until it expires. That is what a stateless session means, and a server-side session store is what
would change it.

A user arriving from a provider for the first time is created and put in whatever groups the
provider's mapping says. They hold nothing until somebody grants it: a stranger who has proved
who they are is still a stranger. Their account has no password — it is signed into somewhere
else, and one here would be a second way in that the directory does not know about and cannot
close.

A provider may be told not to create accounts at all, for a deployment whose directory is much
larger than the set of people who should reach Keydra. It then signs in only people an
administrator prepared, matched by username within that provider.

Whichever way somebody arrived, the session is the same cookie: a name, an expiry, signed and
encrypted. An external provider is a different way of reaching a session, not a different kind of
one — two formats would mean two things to expire, two to revoke, and two to get wrong.

## Enforcement

### One place, not fifty-three

`@RolesAllowed` is on 53 endpoints today, each naming roles. Phase 9 replaces it with a single
annotation naming a permission and where to find the connection it applies to:

```java
@RequiresPermission(value = Permission.KEYS_DELETE, connection = "connectionId")
public Uni<KeyOperationResult> delete(@PathParam("connectionId") Long connectionId, ...)
```

An interceptor resolves the caller's permissions for that connection and refuses with 403 before
the method runs. Endpoints that are about the instance name no connection.

Two details of that interceptor are load-bearing, and both were learned by getting them wrong:

- **The annotation's members are `@Nonbinding`.** CDI matches an interceptor to a method by the
  binding annotation *and its member values*. Without `@Nonbinding` the interceptor matched only
  endpoints requiring the one permission its own annotation happened to name — which was none of
  them. Every annotation in the application was present and inert, and every unit test passed.
- **Every guarded endpoint answers with a `Uni` or a `Multi`.** The check is itself asynchronous,
  so a refusal has to be folded into the returned reactive type; a method answering with a plain
  value gives the interceptor nowhere to put one, and it fails on the request it was meant to
  protect — as a 500 rather than a 403.

Both are now assertions rather than conventions. The list of endpoints is enumerable, which is
what makes them testable: `EndpointCoverageTest` walks every endpoint and fails when one carries
no annotation, when a connection-level permission names a parameter that does not exist, or when
a guarded endpoint answers synchronously. `ScopedAccessTest` makes a request that is actually
refused, because a mechanism can be entirely correct in isolation and matched to nothing.

One thing the interceptor cannot make into a 403: a streamed endpoint. Key enumeration is
server-sent events, and the response is committed before the first item, so a refusal there
arrives as an empty stream rather than a status. Nothing leaks — the refusal happens before the
scan starts — but the status code is imprecise, and the interface finds out from
`/api/v1/auth/permissions` rather than from that response.

### The UI asks rather than assumes

The frontend already hides what a role cannot do. It asks instead: `GET /api/v1/auth/permissions`
answers what the current user may do over the instance and over each target they can see, in one
call, and the navigation, the toolbars and the card menus read from it. A hidden control is a
courtesy — the refusal is the server's — but a UI offering an action that will be refused is a UI
that lied.

While the answer is still unknown, and on an instance enforcing nothing, everything is offered.
Hiding an action from somebody who may take it is worse than showing one that will be refused:
the refusal explains itself, the absence looks like the feature was never built.

### Roles are also a summary

Keydra's older gate is `@RolesAllowed`, which knows three names and nothing about scopes. A
locally authenticated session therefore carries a summary of what its owner holds — the built-in
roles they hold something of, anywhere — so that gate lets through everybody the permission check
would admit. The summary is marked as derived and is never read back as a source of permissions:
doing so would let it outrank what it summarises, and a grant on one server group would become
access to every server.

## What this does not do

- **No row-level rules on keys.** A subject either browses a keyspace or does not. Per-key rules
  belong to the server's own ACLs, which Keydra already manages, and duplicating them here would
  produce two answers to one question.
- **No time-bounded grants.** Useful, and a later phase; a grant that expires needs somewhere to
  say what happens to a session holding it.
- **No approval workflow.** Granting is immediate and audited.
