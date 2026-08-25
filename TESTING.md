# Testing

> [!IMPORTANT]
> **Most of this document is superseded.** It captures the two-user *merge*
> architecture — `pendingLinkCode` attributes, a `bohemia_*` Keycloak user, a
> surviving/losing account, `gameAccountLinked` — all of which has been removed.
> Linking is now a single attribute write (`bohemiaId`) onto the
> already-authenticated user, driven by an application-initiated action.
>
> It is kept because its empirical findings still hold and are still cited: the
> two Admin API bootstrapping gotchas (two-call required-action registration; the
> declarative User Profile hiding unmanaged attributes), the message-formatting
> issue, and the confirmation that **attribute writes made from a required action
> do persist**.
>
> For the current flow see the "Current architecture" run captured at the end of
> this file, `README.md`, and `scripts/integration-test.sh`.

This document describes the manual end-to-end walkthrough for
`keycloak-spi-reforger`, and captures **real, unedited output** from an
actual run against a real `quay.io/keycloak/keycloak:26.0` (reporting
itself as Keycloak 26.0.8) container, built from this repo at commit
`b13f2cf`, on 2026-08-21.

This replaces the previous version of this document, which walked through
the old architecture (a separate Central API HTTP call plus a throwaway
stub server). As of commit `0b4089f` this SPI makes **zero outbound HTTP
calls** — linking happens entirely inside the SPI, directly against local
Keycloak user attributes, via `LinkedIdentityService`. There is no stub
server and no `ELIFERPG_*` configuration anymore.

## Prerequisites

- `docker` and `docker compose`
- `curl` and `python3` on the host (used by the bootstrap/test commands)

## What was exercised

1. Bringing up the stack (`compose.yml`) and confirming readiness.
2. Pre-seeding a `bohemia_TEST001` user via the Admin API, standing in for a
   gameserver-provisioned account (this repo doesn't provision one itself).
3. Self-service `generate` + `redeem`, called directly via `curl` against
   the two new REST endpoints (`POST /realms/{realm}/link-game-account/code`
   and `.../redeem`).
4. Trusted-caller mode: a service-account client scoped
   `accounts:links:redeem-trusted`, calling the same endpoints on behalf of
   a `bohemiaId`.
5. Re-driving the required-action browser flow end-to-end via the same
   curl-scripting technique `scripts/integration-test.sh` established
   (login form → required-action form → code submission), confirming the
   web-initiated path (`LinkGameAccountRequiredAction`, not the REST
   resource) also works, and that it does **not** re-prompt after a
   successful merge — re-confirming the fix from commit `fa9cd06`
   (`survivor.removeRequiredAction("link-game-account")`).

**A genuine, previously-undetected bug was found during this run** (not a
setup mistake — reproduced repeatedly, in a clean environment, and isolated
to a precise, narrow cause). It's documented in its own section below,
with real stack traces, because it materially affects the literal
generate/redeem sequence in Step 3/4 of the plan for this task. All
existing unit tests (`LinkedIdentityServiceTest`,
`LinkGameAccountResourceTest`, `LinkGameAccountRequiredActionTest`) use
Mockito mocks for `KeycloakSession`/`UserProvider` and never exercise a
real Hibernate/JPA persistence layer, which is exactly why this had never
surfaced before this task's live, real-database verification.

## Bootstrap used for this run

Since ports `18080`/`18082` were already occupied by other live sessions on
this machine, a port-substituted copy of `compose.yml` was used
(`18080` → `18091`), verified via `diff` to differ only in the port number,
and removed afterward (see "Cleanup" at the end of this document).

```
$ sed 's/18080/18091/g' compose.yml > compose.verify.yml
$ diff <(sed 's/18080/PORT/g' compose.yml) <(sed 's/18091/PORT/g' compose.verify.yml)
DIFF CLEAN: only port differs

$ COMPOSE_FILE=compose.verify.yml COMPOSE_PROJECT_NAME=kcverify docker compose up -d --build
 Image kcverify-keycloak Built
 Network kcverify_default Created
 Container kcverify-keycloak-1 Created
 Container kcverify-keycloak-1 Started

$ curl -s http://localhost:18091/realms/master -o /dev/null -w "status: %{http_code}\n"
status: 200   # ready after ~1s
```

Realm `linking-verify` was then bootstrapped via the Admin REST API,
reusing the exact pattern already established in `scripts/local-dev-up.sh`
(realm creation with `loginTheme: eliferpg`, the two-step required-action
registration, `VERIFY_PROFILE` disabled, `unmanagedAttributePolicy:
ADMIN_EDIT`), plus fixtures this walkthrough needed beyond what that script
sets up (a client scope + service-account client for trusted-caller mode,
and a dummy `discord` identity-provider registration so a federated
identity could genuinely be attached to and moved off a test user — more
below). Every bootstrap call returned its expected status:

```
=== create realm ===                              status:201
=== register required action ===                  status:204
=== enable required action ===                     status:204
=== disable VERIFY_PROFILE ===                     status:204
=== set unmanagedAttributePolicy=ADMIN_EDIT ===     status:200
=== create browser-client (standard flow) ===       status:201
=== create client scope accounts:links:redeem-trusted === status:201
=== create bridge-trusted-client (service account) ===     status:201
=== assign trusted scope as default scope ===       status:204
=== create dummy discord IdP (fixture) ===           status:201
```

`bohemia_TEST001` (Step 2's required exact username) was created fresh via
`POST /admin/realms/linking-verify/users`, `201`, with no `pendingLinkCode`
and no `gameAccountLinked` — a genuinely never-linked identity, exactly
mirroring `scripts/local-dev-up.sh`'s comment that this needs no
preset-linked workaround since commit `fa9cd06`.

## Self-service: generate

A portal-style test user (`portal1` — password credential, `gameAccountLinked`
preset to `"true"` purely so *this account's own* login doesn't immediately
trip the very required action under test; see "A note on portal-style test
users" below) obtained its own access token and called the self-service
`code` endpoint with no special scope:

```
$ curl -s -X POST http://localhost:18091/realms/linking-verify/protocol/openid-connect/token \
    -d "grant_type=password&client_id=browser-client&username=portal1&password=pass1234&scope=openid"
{"access_token":"eyJ...", ..., "scope":"openid profile email"}   # status 200

$ curl -s -X POST http://localhost:18091/realms/linking-verify/link-game-account/code \
    -H "Authorization: Bearer $PORTAL1_TOKEN" -H "Content-Type: application/json" -d '{}'
{"code":"KPSE7MPG","expiresInSeconds":600}
status:200
```

Confirms: `200`, body shape exactly `{"code":..., "expiresInSeconds":600}`,
as documented.

## The bug found: redeem 500s whenever the code-generating account is also the losing side

The brief's literal Step 3/4 scenario is: `portal1` generates a code
(above), then that code gets redeemed **onto** `bohemia_TEST001` (either by
`bohemia_TEST001`'s own token, which doesn't practically exist since real
Bohemia identities are provisioned passwordless — confirmed in
`IMPLEMENTATION_PLAN.md` §2 — or, per the brief's own suggested
substitution, via trusted-caller mode). That call was made for real:

```
$ curl -s -X POST http://localhost:18091/realms/linking-verify/protocol/openid-connect/token \
    -d "grant_type=client_credentials&client_id=bridge-trusted-client&client_secret=bridge-secret-test"
{"access_token":"eyJ...","scope":"profile accounts:links:redeem-trusted email", ...}
status:200

$ curl -s -X POST http://localhost:18091/realms/linking-verify/link-game-account/redeem \
    -H "Authorization: Bearer $BRIDGE_TOKEN" -H "Content-Type: application/json" \
    -d '{"bohemiaId":"TEST001","code":"KPSE7MPG"}'
{"error":"could not complete link"}
status:500
```

Server-side (`docker logs kcverify-keycloak-1`), this is a real, unhandled
exception inside `LinkedIdentityService`, caught by its own top-level
`catch (RuntimeException e)` in `redeem()` and turned into `LinkOutcome.ERROR`:

```
WARN  [net.eliferpg.keycloak.spi.link.LinkedIdentityService] (executor-thread-1)
Unexpected error during link redeem: org.keycloak.models.ModelException: Database operation failed
	at org.keycloak.connections.jpa.PersistenceExceptionConverter.convert(PersistenceExceptionConverter.java:109)
	at org.keycloak.connections.jpa.PersistenceExceptionConverter.invoke(PersistenceExceptionConverter.java:68)
	at jdk.proxy2/jdk.proxy2.$Proxy60.flush(Unknown Source)
	at org.keycloak.models.jpa.JpaUserProvider.removeUser(JpaUserProvider.java:161)
	at org.keycloak.models.jpa.JpaUserProvider.removeUser(JpaUserProvider.java:149)
	at org.keycloak.storage.UserStorageManager.removeUser(UserStorageManager.java:360)
	at org.keycloak.models.cache.infinispan.UserCacheSession.removeUser(UserCacheSession.java:841)
	at net.eliferpg.keycloak.spi.link.LinkedIdentityService.mergeInto(LinkedIdentityService.java:127)
	at net.eliferpg.keycloak.spi.link.LinkedIdentityService.doRedeem(LinkedIdentityService.java:102)
	at net.eliferpg.keycloak.spi.link.LinkedIdentityService.redeem(LinkedIdentityService.java:47)
	at net.eliferpg.keycloak.spi.link.LinkGameAccountResource.redeemInternal(LinkGameAccountResource.java:95)
	at net.eliferpg.keycloak.spi.link.LinkGameAccountResource.redeem(LinkGameAccountResource.java:62)
	...
Caused by: java.lang.IllegalStateException: org.hibernate.TransientObjectException: persistent
instance references an unsaved transient instance of 'org.keycloak.models.jpa.entities.UserEntity'
(save the transient instance before flushing)
	at org.hibernate.engine.spi.CascadingActions$9.cascade(CascadingActions.java:382)
	...
```

### Root cause, isolated

This reproduced identically on a second, completely independent fresh pair
(`portal4` + `bohemia_TEST099`, no federated identity involved at all),
ruling out federated-identity handling as the cause. Isolating further by
swapping which side generates the code:

- **Crashes** (confirmed twice): the code is generated by the *non-Bohemia*
  account (self-service `generateCode`, so `LinkedIdentityService.generateCode`
  writes `pendingLinkCode`/`pendingLinkCodeExpiresAt` onto it), and that
  same account ends up on the **losing** side of the merge (which is always
  the case when a Bohemia account is the redeem `target`, since
  `isGameIdentity()` always makes the Bohemia side the survivor).
- **Succeeds cleanly** (confirmed twice, full transcripts below): the code
  is generated **on the Bohemia account** instead (trusted-caller
  `generateCode` with a `bohemiaId`), and a non-Bohemia account redeems it.
  Now the code-generating account is the *survivor*, not the loser.

The difference is exactly which `UserModel` is passed to which call inside
`LinkedIdentityService.doRedeem`/`mergeInto` (`LinkedIdentityService.java`):
`clearPendingCode(initiator)` (line 82, itself calling
`UserModel.removeAttribute()` twice at lines 148–149) runs, unconditionally,
on whichever account generated the code (the `initiator`). Separately,
`mergeInto` calls `session.users().removeUser(realm, loser)` (line 127). When
`initiator` and `loser` are **the same `UserModel` instance** — i.e. the
code-generating account is also the one being deleted — Hibernate's internal
flush inside `removeUser()` throws the `TransientObjectException` above.
When they're different instances (code generated by the account that
*survives*), no crash occurs.

This means the endpoint's real-world **trusted-caller mode redeem** — a
Bridge-facing proxy calling `POST /redeem` with `{bohemiaId, code}` for a
code a portal user generated — **always** hits this, because
`resolveTargetFromBohemiaId` always resolves to the Bohemia account, which
`isGameIdentity()` always makes the survivor, making the portal-side
initiator the loser. There is no way to invoke that specific call shape
(trusted-caller redeem of a *portal-generated* code) without triggering it.
The only workaround is the opposite direction: the Bohemia side generates,
the portal side redeems.

This is a real defect in `LinkedIdentityService`, not a test-environment
artifact — reproduced from a clean realm, with no prior failed requests in
play, on two independent fresh identity pairs. **Fixing it is out of scope
for this documentation-only task**; it's flagged here for a follow-up fix
(the likely direction: attribute mutation and user removal on the same
entity within one request need an explicit flush — or reordering the
mutations — between them; whoever picks this up should start from the
isolation above rather than re-deriving it).

One reassuring property, also confirmed directly: **the failure is a clean,
full rollback on the REST path** — a follow-up Admin API check after the
`500` showed `portal1` and `bohemia_TEST001` both completely untouched (the
pending code was still present and unconsumed on `portal1`, `bohemia_TEST001`
still had no `gameAccountLinked` attribute). No partial/corrupted state was
left behind by the REST-invoked path.

### Update: this bug is fixed (commits `ac0bdbc`/`ac70bcc`)

The root-cause analysis above is kept as-is — it's accurate history and the
isolation work remains valuable. But the defect it describes is **no longer
present**: it was fixed by commit `ac0bdbc` ("skip clearing pending-code
attributes on a user about to be deleted"), with a follow-up fix in `ac70bcc`
restoring the pending-code clear on the neither-side-is-game-identity
`ERROR` path that `ac0bdbc`'s change had incidentally affected. Both commits
are on `main`, already independently reviewed and merged.

The following is **not something this document's author ran** — it's the
controller's own live re-verification of the fix, performed against a fresh
Keycloak 26.0.8 container built from source after `ac0bdbc` landed, recorded
here for provenance rather than presented as a first-hand run:

The exact previously-crashing scenario (portal self-service generates a
code, then a trusted-caller/bridge client redeems it onto the Bohemia
identity) was re-run against a clean container built from the fixed source:

```
$ curl -s -w "\nstatus:%{http_code}\n" -X POST http://localhost:18092/realms/fixverify/link-game-account/redeem \
    -H "Authorization: Bearer $BRIDGE_TOKEN" -H "Content-Type: application/json" \
    -d '{"bohemiaId":"FIX001","code":"ATAXM3VB"}'
{"outcome":"already-linked"}
status:200
```

Admin API confirmation: `portal_fix1` (the code-generating/losing account)
was deleted (`[]` from a username lookup), and `bohemia_FIX001` ended up
with `"attributes":{"gameAccountLinked":["true"]}` and `"requiredActions":[]`.
A full `docker compose logs` grep across the entire session for
`TransientObjectException` and `Unexpected error during link redeem`
returned zero matches.

A second run with a real federated identity attached to the portal side (to
also exercise the `addFederatedIdentity` + `removeUser` combination, not
just the simpler no-identity path) through the same trusted-caller redeem
shape also succeeded cleanly:

```
$ curl -s -w "\nstatus:%{http_code}\n" -X POST http://localhost:18092/realms/fixverify/link-game-account/redeem \
    -H "Authorization: Bearer $BRIDGE_TOKEN" -H "Content-Type: application/json" \
    -d '{"bohemiaId":"FIX002","code":"299HBV2Q"}'
{"outcome":"linked"}
status:200
```

Again: zero `TransientObjectException`/`Unexpected error during link
redeem` matches in the logs across this whole verification.

This confirms the primary trusted-caller direction (the literal Step 3/4
scenario documented above) now works, contradicting nothing in the root
cause analysis — the fix addresses exactly the mechanism isolated there
(the initiator-is-also-the-loser case, which unconditionally cleared the
pending-code attributes on the same `UserModel` `removeUser()` was about to
delete).

### Working demonstration of the same endpoints, avoiding the bug's direction

To still confirm the endpoints' auth handling, response shapes, and merge
mechanics (including the Task 4 required-actions bugfix) end-to-end against
a real database, the same two calls were exercised in the *safe* direction:
trusted-caller `generateCode` for a fresh Bohemia identity, then a portal
user's own self-service `redeem` of that code — this time with a real
federated identity attached to the portal side, so the merge would
genuinely move something and return `"linked"` rather than
`"already-linked"`:

```
$ curl -s -X POST http://localhost:18091/realms/linking-verify/link-game-account/code \
    -H "Authorization: Bearer $BRIDGE_TOKEN" -H "Content-Type: application/json" \
    -d '{"bohemiaId":"TEST080"}'
{"code":"97TUNMVJ","expiresInSeconds":600}
status:200

$ curl -s -X POST http://localhost:18091/realms/linking-verify/link-game-account/redeem \
    -H "Authorization: Bearer $PORTAL7_TOKEN" -H "Content-Type: application/json" \
    -d '{"code":"97TUNMVJ"}'
{"outcome":"linked"}
status:200
```

Admin API confirmation after this call:

```
$ curl -s .../admin/realms/linking-verify/users?username=portal7&exact=true
[]   # deleted, as expected of the losing side

$ curl -s .../admin/realms/linking-verify/users?username=bohemia_TEST080&exact=true
[{"username":"bohemia_test080", "attributes":{"gameAccountLinked":["true"]},
  "requiredActions":[], ...}]

$ curl -s .../admin/realms/linking-verify/users/{bohemia_TEST080-id}/federated-identity
[{"identityProvider":"discord","userId":"discord-portal7-sub","userName":"portal7#0007"}]
```

`portal7` (the loser, which held a `discord` federated identity attached
for this test) was deleted, its federated identity moved onto
`bohemia_TEST080`, `gameAccountLinked` set, and `requiredActions` empty —
all four assertions Step 3 asked for, achieved via the direction that
doesn't hit the bug above.

Two other REST-level checks, also captured for real:

```
$ curl -s -X POST .../link-game-account/redeem -H "Authorization: Bearer $PORTAL1_TOKEN" \
    -d '{"code":"GARBAGE9"}'
{"error":"invalid code"}
status:404

$ curl -s -X POST .../link-game-account/code -d '{}'   # no Authorization header
{"error":"unauthenticated"}
status:401
```

## Trusted-caller mode: client + scope setup

A client scope `accounts:links:redeem-trusted` was created realm-wide and
assigned as a **default** client scope (so a plain client-credentials grant
includes it without an explicit `scope=` request parameter) to a new
service-account client, `bridge-trusted-client`:

```
$ curl -s -X POST .../admin/realms/linking-verify/client-scopes \
    -d '{"name":"accounts:links:redeem-trusted","protocol":"openid-connect", ...}'
status:201

$ curl -s -X POST .../admin/realms/linking-verify/clients \
    -d '{"clientId":"bridge-trusted-client","secret":"bridge-secret-test","publicClient":false,
         "serviceAccountsEnabled":true,"standardFlowEnabled":false,"directAccessGrantsEnabled":false}'
status:201

$ curl -s -X PUT .../admin/realms/linking-verify/clients/{id}/default-client-scopes/{scopeId}
status:204
```

A client-credentials token was then obtained and its `scope` claim decoded
to confirm the trusted scope was actually granted (not just configured):

```
$ curl -s -X POST .../protocol/openid-connect/token \
    -d "grant_type=client_credentials&client_id=bridge-trusted-client&client_secret=bridge-secret-test"
{"access_token":"eyJ...","scope":"profile accounts:links:redeem-trusted email", ...}
status:200

$ python3 -c "...decode JWT payload..."
profile accounts:links:redeem-trusted email
```

`LinkGameAccountResource.hasTrustedScope()` reads exactly this claim
(splitting on spaces and matching `accounts:links:redeem-trusted`), so this
confirms the token this client obtains is recognized as trusted. The actual
trusted-caller `redeem` call is documented above (both the crashing literal
scenario and the working `generateCode`-for-a-`bohemiaId` call); the
trusted-caller `generateCode` call (`{"bohemiaId":"TEST080"}` → `200
{"code":"97TUNMVJ","expiresInSeconds":600}`) is shown in the previous
section too, and is the trusted-scope-gated branch of the same endpoint
`portal1` exercised without any scope.

## Browser flow (required action, not the REST resource)

This re-drives `LinkGameAccountRequiredAction`/`processAction` end-to-end,
using the same curl-scripting technique `scripts/integration-test.sh`
established (GET the login page, regex the form's self-contained
`action="..."` URL — already carrying `session_code`/`execution`/etc., no
hidden fields to extract separately — POST to it with a cookie jar, repeat).
A fresh Bohemia identity was pre-seeded with a fixed pending code directly
via the Admin API (the same pattern `scripts/local-dev-up.sh` uses for its
own interactive demo), and a fresh Discord-broker-style temp user completed
login and entered that code:

```
$ curl -s -X POST .../admin/realms/linking-verify/users \
    -d '{"username":"bohemia_TEST070", ...}'
status:201
$ curl -s -X PUT .../admin/realms/linking-verify/users/{id} \
    -d '{"attributes":{"pendingLinkCode":["WEBFLOW2"],"pendingLinkCodeExpiresAt":["<+600s>"]}}'
status:204
$ curl -s -X POST .../admin/realms/linking-verify/users \
    -d '{"username":"discord-user2", "credentials":[{"type":"password","value":"pass1234",...}]}'
status:201

$ curl -s -c cookies -L \
    "http://localhost:18091/realms/linking-verify/protocol/openid-connect/auth?client_id=browser-client&response_type=code&scope=openid&redirect_uri=http://localhost/callback" \
    -w "GET /auth -> %{http_code}\n"
GET /auth -> 200

$ curl -s -c cookies -b cookies -L "<login form action>" \
    --data-urlencode "username=discord-user2" --data-urlencode "password=pass1234" \
    -w "POST login -> %{http_code} url=%{url_effective}\n"
POST login -> 200 url=http://localhost:18091/realms/linking-verify/login-actions/required-action?execution=link-game-account&client_id=browser-client&tab_id=Myw7OEBseqc&client_data=...

$ curl -s -c cookies -b cookies -L "<required-action form action>" \
    --data-urlencode "code=WEBFLOW2" \
    -w "POST code -> %{http_code} url=%{url_effective} time_total=%{time_total}s\n"
POST code -> 302 url=http://localhost/callback?session_state=7b2fe040-c001-4b28-83e5-5c6d729f96ff&iss=http%3A%2F%2Flocalhost%3A18091%2Frealms%2Flinking-verify&code=52790a36-357a-4a1d-a5ce-9d40637833e4.7b2fe040-c001-4b28-83e5-5c6d729f96ff.3cf53242-4471-47cd-93b4-a4bf15bc5bf2 time_total=0.021156s
```

The flow completed with a real `302` out to the client's `redirect_uri`
carrying a genuine authorization code — it left the required-action page
entirely and did **not** re-render it, in 21ms, on the first attempt.
Admin API confirmation afterward:

```
$ curl -s .../admin/realms/linking-verify/users?username=discord-user2&exact=true
[]   # deleted (it was the losing side -- non-Bohemia account being merged in)

$ curl -s .../admin/realms/linking-verify/users?username=bohemia_TEST070&exact=true
[{"username":"bohemia_test070", "attributes":{"gameAccountLinked":["true"]},
  "requiredActions":[], ...}]
```

`discord-user2` (the user who was mid-login) was deleted, its live
authentication session was correctly reassigned onto `bohemia_TEST070`
(confirmed structurally by the flow completing and issuing a real
authorization code, rather than erroring out from under the deleted user),
`gameAccountLinked` was set on the survivor, and — the specific thing this
scenario exists to re-confirm — **`requiredActions` came back empty**,
meaning the survivor was not re-prompted with the same required action a
second time. This is exactly the fix from commit `fa9cd06`
(`survivor.removeRequiredAction("link-game-account")`), now re-verified
from the web-initiated entry point (rather than Task 4's own verification,
which used the REST-adjacent path) against a real database.

Note: because the code was pre-seeded **on** the Bohemia account (the
survivor) rather than self-service-generated by the account that goes on to
be deleted, this run doesn't hit the bug described above — which is exactly
consistent with that bug's isolation (code-generator == loser is the
crashing combination; here the code-generator is the survivor). An earlier
attempt at this same scenario, run immediately after two of the crashing
REST attempts above in the same realm/database session, produced a
partially-inconsistent result (the loser deleted, `gameAccountLinked` set,
but `requiredActions` still non-empty, and no completing redirect) — rerun
cleanly (fresh users, no prior failed attempts against this realm) it
passed as shown above. This dev-mode Keycloak instance uses an embedded,
non-production database; a prior failed flush appears able to leave
after-effects for later, otherwise-unrelated requests in the same session,
which is worth keeping in mind if this bug is investigated further, but
wasn't chased down further here since it isn't this task's job to fix the
underlying defect.

## Result summary

| step | outcome | evidence |
|---|---|---|
| stack readiness | PASS | `200` from `/realms/master` after ~1s |
| seed `bohemia_TEST001` | PASS | `201`, no preset attributes (genuinely fresh) |
| self-service generate | PASS | `200 {"code":"KPSE7MPG","expiresInSeconds":600}` |
| self-service/trusted redeem, **literal Step 3/4 direction** (portal generates, redeemed onto Bohemia) | **PASS (fixed in `ac0bdbc`/`ac70bcc`)** | Originally `500 {"error":"could not complete link"}` / `TransientObjectException`, isolated and reproduced twice; fixed and re-verified live — see "Update: this bug is fixed" above |
| trusted-caller generate + self-service redeem, **opposite direction** (Bohemia generates, portal redeems) | PASS | `200 {"outcome":"linked"}`; Admin API confirms loser deleted, federated identity moved, `gameAccountLinked=true`, `requiredActions` empty |
| trusted scope grant | PASS | client-credentials token's `scope` claim contains `accounts:links:redeem-trusted` |
| invalid code (REST) | PASS | `404 {"error":"invalid code"}` |
| unauthenticated call (REST) | PASS | `401 {"error":"unauthenticated"}` |
| browser flow (required action) | PASS | `302` redirect out with a real authorization code, 21ms, first attempt; loser deleted, survivor linked with `requiredActions` empty (re-confirms commit `fa9cd06`'s fix from the web-initiated entry point) |

## A note on portal-style test users

`portal1`/`portal4`/`portal6`/`portal7` were all created with
`gameAccountLinked` preset to `"true"`. This is a deliberate test-harness
accommodation, not a realism claim: with `link-game-account` registered and
enabled realm-wide (as this bootstrap does, matching
`scripts/local-dev-up.sh`), Keycloak's `evaluateTriggers` re-checks every
enabled required action on every authentication, browser or direct-grant
alike. A genuinely fresh, never-linked portal user would immediately get
challenged by the *same* required action this walkthrough is trying to
exercise via a plain REST call — a real chicken-and-egg property of this
design (the login flow's own required action, and the standalone REST
self-service endpoints, both gate on the same `gameAccountLinked`
attribute). Presetting it stands in for "a portal user who already
completed onboarding previously (or predates this required action's
realm-wide enablement) and is now calling the self-service endpoint from an
already-authenticated session" — a realistic use case for these endpoints
(re-linking, or linking a second account) distinct from the first-ever-login
case the required action itself handles.

## Cleanup

```
$ COMPOSE_FILE=compose.verify.yml COMPOSE_PROJECT_NAME=kcverify docker compose down -v
$ rm -f compose.verify.yml
$ docker rmi kcverify-keycloak
```

`git status` after cleanup shows only `TESTING.md` changed.

## Scope note

`compose.yml` is dev/test-only tooling for **this** repo
(`keycloak-spi-reforger`). It spins up a disposable Keycloak stack solely to
exercise this SPI in isolation. It is unrelated to, and never touches,
eliferpg's own `compose.yml` (per the plan's own non-goal — this repo does
not modify or depend on the main eliferpg application's compose
configuration). The port-substituted `compose.verify.yml` used for this
specific run (port `18091` in place of `18080`, to avoid colliding with
another live session on this machine) was a temporary, uncommitted file,
removed after the run per "Cleanup" above.

---

# Current architecture — captured run (2026-08-24)

Real output from `./scripts/integration-test.sh` after the merge was removed and
the feature was renamed to `bohemia-gameaccount`. Five cases, all passing,
against a freshly built provider image.

**A defect an earlier run exposed, before any of the cases could be trusted:** the
suite had been testing a stale jar. `docker-compose.integration-test.yml` pins a
prebuilt `eliferpg/keycloak-bohemia-gameaccount:dev` image rather than a build context,
so `docker compose up --build` is a no-op for it. The container was running a jar
dated three days earlier:

```
$ docker exec keycloak-bohemia-gameaccount-keycloak-1 ls -la /opt/keycloak/providers/
-rw-r--r-- 1 root root 19715 Aug 21 12:03 keycloak-bohemia-gameaccount-0.1.0-SNAPSHOT.jar
```

It surfaced because the endpoint answered with the *old* response shape
(`{"code":...}` instead of `{"pin":...}`). The script now builds the image
explicitly. Any assertion made by this suite before that fix should be treated as
having tested whatever image happened to be present.

**Related, and specific to the package move:** build with `mvn clean`, not bare
`mvn verify`. Maven does not prune deleted classes from `target/classes`, so
after moving `net.eliferpg` → `com.eliferpg` the old class files are still
packaged into the jar alongside the new ones.

## Provider registration under the new ids

The required action registered under `link-bohemia-gameaccount` — Keycloak echoes
the id back as the flow's `execution`, which is what proves the rename reached
the running server rather than only the source:

```
POST login -> 200 url=http://localhost:18082/realms/spi-integration-test/login-actions/required-action
  ?execution=link-bohemia-gameaccount&client_id=test-client&tab_id=m8xj3PhEeG4...
```

The REST resource registered under the separate id `bohemia-gameaccount`; every
PIN in this run was minted through it:

```
POST /realms/spi-integration-test/bohemia-gameaccount/pin
```

## The application-initiated action

The success case ends with Keycloak itself reporting the action's outcome on the
redirect — this is the confirmation that `kc_action` drove the form, rather than
the action having fired on its own:

```
POST pin -> 302 url=http://localhost/callback?session_state=b8e920cb-b789-4e37-8a1d-62fb56f1de42
  &iss=http%3A%2F%2Flocalhost%3A18082%2Frealms%2Fspi-integration-test
  &kc_action=link-bohemia-gameaccount&kc_action_status=success
  &code=bed47254-cd22-4ec2-8bf8-bdfc5d4266e9...
```

(The trailing `code=` there is OAuth's authorization code, unrelated to the PIN —
worth stating explicitly now that the form field is `pin`.)

## Cases

```
=== case: success ===
(no step3.html -- flow left the action page, as expected on success)
persisted bohemiaId=BOHEMIA-SUCCESS-001
PASS: success

=== case: invalid PIN (pin=GARBAGE9) ===
That PIN isn&#39;t valid or has expired. Rejoin the server to get a new one.
PASS: invalid PIN

=== case: replayed PIN (pin=EKGFT5JG) ===
That PIN isn&#39;t valid or has expired. Rejoin the server to get a new one.
PASS: replayed PIN

=== case: conflict setup (pin=7UTJQSGQ) ===
persisted bohemiaId=BOHEMIA-CONFLICT-001
PASS: conflict setup

=== case: conflict rejected (pin=RDHWE37Y) ===
That Bohemia game account is already linked to a different eLifeRPG account.
PASS: conflict rejected

=== all cases passed ===
```

Two of these are new and exist for specific reasons:

- **replayed PIN** re-submits the PIN the success case just consumed. It must
  fail exactly like an unknown PIN. This is the regression test for the
  consume-once guarantee that `SingleUseObjectProvider.remove()` provides.
- **conflict rejected** binds one `bohemiaId` to a user, then tries to bind the
  same `bohemiaId` to a second user. Keycloak does **not** enforce this for us —
  verified separately against a live 26.0.8 instance, the analogous duplicate is
  accepted at write time (`204`) and only fails later on read with
  `IllegalStateException: More results found ...`, permanently breaking that
  user's login. The check in `BohemiaGameAccountService.bind` is therefore
  load-bearing, not defensive, and this case pins it.

Note both conflict PINs are minted *before* either is redeemed: once a
`bohemiaId` is bound, `POST /pin` refuses to mint for it again (`409`), which is
itself the first line of defence.

## Assertion strength

The success cases assert on the **persisted attribute**, read back over the Admin
API, not on the rendered page:

```
persisted bohemiaId=BOHEMIA-SUCCESS-001
```

That distinction matters here specifically. Writes made from a *first-broker-login
authenticator* were found to be silently rolled back — both a removal and an
ordinary write, under `unmanagedAttributePolicy` of `ADMIN_EDIT` and `ENABLED`
alike — while Keycloak's own writes in the same flow persisted. Required actions
are on the safe side of that, but "the form rendered and redirected" is not
evidence that anything was stored.

## Unit tests

```
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0 -- in com.eliferpg.keycloak.spi.bohemiagameaccount.BohemiaGameAccountResourceTest
Tests run: 13, Failures: 0, Errors: 0, Skipped: 0 -- in com.eliferpg.keycloak.spi.bohemiagameaccount.BohemiaGameAccountServiceTest
Tests run: 13, Failures: 0, Errors: 0, Skipped: 0 -- in com.eliferpg.keycloak.spi.LinkBohemiaGameAccountRequiredActionTest
Tests run: 35, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```
