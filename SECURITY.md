# Security policy

Keydra holds the credentials to every server it manages. That makes it worth attacking, and
it makes a report worth reading carefully.

## Supported versions

Keydra has no tagged release yet. Until it does, only `main` is supported: fixes land there
and there is nothing else to backport to.

| Version | Supported |
|---|---|
| `main` | yes |
| anything else | no |

## Reporting a vulnerability

**Do not open a public issue.**

Use GitHub's private reporting — *Security* → *Report a vulnerability* on this repository.
It opens a channel only the maintainers can read, and it is the fastest route.

If that is unavailable to you, email the maintainers listed on the organisation profile at
<https://github.com/keydrahq> instead.

Please include:

- what you did, in enough detail to repeat it;
- what happened, and what you expected;
- the commit or image tag you were on;
- whether the instance had `KEYDRA_SECURITY_ENABLED` on, and whether it was behind a proxy.

A proof of concept helps. A working exploit is not required and please do not publish one.

## What to expect

- An acknowledgement within three working days.
- An assessment within ten, saying whether we agree it is a vulnerability and what severity we
  think it is.
- A fix on `main`, and credit in the release notes unless you would rather not be named.

Please give us a reasonable window before disclosing publicly. If we go quiet, say so in the
thread — silence is a failure on our side, not a signal to stay quiet on yours.

## What is in scope

Anything that lets somebody:

- reach a target, or a credential for one, that their grants do not cover;
- read another account's session, second factor or recovery codes;
- get a secret out of the API, a log, a metric label or a notification payload;
- run code on the machine Keydra runs on, or on a target, through the console's deny-list or
  a migration script;
- make Keydra fetch an address it should refuse — link-local in particular;
- bypass the approval requirement, the guarded-target name check, or the sign-in throttle.

## What is not

- **`KEYDRA_SECURITY_ENABLED=false`.** It admits everybody, on purpose, and every page says
  so. Running it exposed is a deployment mistake rather than a vulnerability.
- **What an administrator can do.** An account holding `admin` can reach everything; that is
  what the role is. A privilege escalation *into* it is in scope.
- **What a target's own ACL allows.** Keydra does not have per-key permissions of its own —
  the server's ACLs answer that, and two answers to one question would be worse than one.
- **Denial of service from a signed-in account with a legitimate grant**, such as a large
  scan. Report it as a performance issue; we will still want to know.
- Findings from an automated scanner with no demonstrated impact.

## What Keydra already does

Useful to know before reporting, and useful to read as a list of things to try to break.

- Stored credentials are AES-256-GCM under an instance key that can be rotated without
  downtime. Account passwords are Argon2id, verified off the event loop.
- A session is a database row, not only a cookie: ending one takes effect on the next request,
  and setting a password ends every session that came before it.
- Permissions are rebuilt per request, so revoking access does not wait for a sign-out.
- Every outbound address somebody typed goes through one guard. Link-local is refused and has
  no setting to allow it.
- A notification about one target reaches only the sockets whose owner may see that target.
- Every sign-in attempt is counted per account and per network, before the hash rather than
  after it.
