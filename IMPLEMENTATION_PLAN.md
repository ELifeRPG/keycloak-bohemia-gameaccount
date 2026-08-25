# keycloak-eliferpg-spi — Implementation Plan

> **Repo renamed:** this project was originally scaffolded as
> `keycloak-eliferpg-spi` and was later renamed to `keycloak-spi-reforger`
> (see `pom.xml`'s `artifactId` and this repo's actual directory name for
> the current name). This document is left as the historical spec/plan
> record and was not mechanically re-titled throughout — where it says
> `keycloak-eliferpg-spi`, read it as this repo under its original name.

> **For agentic workers:** this document is self-contained — you are not
> expected to have access to the `eliferpg` workspace's conversation
> history. Everything you need to know about the surrounding system is
> restated here. If you need ground truth beyond what's written here (e.g.
> exact realm config, exact Central API endpoint shapes as actually
> implemented), the eliferpg workspace lives at a sibling path one level up
> from this repo (`../backend`, `../webapp`, etc.) — read `../backend/ARCHITECTURE.md`
> and `../backend/docs/superpowers/specs/2026-08-14-gameserver-web-identity-linking-design.md`
> if you have access to that path; if you don't, the summaries below are
> the authoritative substitute.

## 1. What this project is

A custom Keycloak **Required Action Provider** — a small Java extension
that plugs into Keycloak's authentication flow — for a Keycloak realm
called `eliferpg`, belonging to a game backend for a custom ArmA Reforger
mod ("eLifeRPG"). It adds a step to the login flow: **"Link Game Account"**
— the user is prompted for a short PIN/code, generated on the game/gameserver
side, that proves they own a specific in-game character. On successful
verification (a server-to-server call this provider makes to eliferpg's own
backend API), the player's web identity (a Discord login, brokered through
Keycloak) becomes linked to their in-game identity.

**Why this exists / why it's a Keycloak extension and not just a web page:**
eliferpg already has a simpler, fully-designed alternative to this — PIN
verification handled entirely in its own backend, with Keycloak used only
passively (see `../backend/docs/superpowers/specs/2026-08-14-gameserver-web-identity-linking-design.md`,
Part 2). That alternative was deliberately not chosen for this build; the
project decided to place PIN verification inside Keycloak's own login flow
instead, as a forcing function (a portal user cannot finish authenticating
without completing this step, if configured as a required action rather
than a self-service action) and to keep the interaction inside Keycloak's
UI shell. **You do not need to re-litigate that decision** — it's already
made. What you're building is the SPI side of it.

## 2. System context (restated, standalone)

- **Keycloak**: version **26.0**, patch **26.0.8** confirmed live at the
  time this decision was made. Self-hosted via Docker
  (`quay.io/keycloak/keycloak:26.0`), realm name `eliferpg`, run today with
  `start-dev --import-realm --features=token-exchange,admin-fine-grained-authz`.
  Realm config is 100% declarative JSON import today
  (`eliferpg-realm.json`) — **no custom Java/SPI code exists anywhere in
  the eliferpg workspace yet.** This SPI is the first one.
- **Central API** ("eliferpg-core"): ASP.NET Core, .NET 11 preview, a
  modular monolith. The module relevant here is `Accounts`
  (`src/Accounts/Accounts.{Domain,Application,Infrastructure,Api}`).
  Player/portal accounts are represented by an `Account` aggregate with a
  `KeycloakUserId` and a nullable `BohemiaId` (the in-game identity). CQRS
  via a source-generated `Mediator` library. Event-sourced on Marten
  (Postgres).
- **Existing identity model**: gameserver players get an
  auto-provisioned, **passwordless** Keycloak user (username
  `bohemia_{bohemiaId}`), created via Keycloak's Admin REST API by
  `Accounts.Infrastructure/Common/KeycloakUserProvisioner.cs`. A future web
  portal lets a human log in via Discord (a standard OAuth2/OIDC identity
  broker, registered as a vanilla Keycloak Identity Provider — no custom
  code, out of scope for this project). **This SPI's job is the bridge
  between those two**: after a portal user logs in via Discord, get them to
  prove they also own a specific in-game account, by entering a PIN.
- **The PIN itself**: generated and validated by the Central API, not by
  this SPI. The Central API's design (see §3) already has a `LinkingCode`
  concept — a short-lived (~10 min), single-use, server-generated code
  mapping `Code → the account that generated it`. Either side (in-game, via
  the Bridge process next to the ArmA Reforger dedicated server; or portal,
  via the website) can generate a code and show it to the player; the
  *other* side is where the player types it in. **This SPI is one possible
  "other side"** — Keycloak's own login flow, instead of a webapp page.

## 3. Goals

- Implement a Keycloak `RequiredActionProvider` (+ `RequiredActionFactory`)
  named something like `link-game-account` that:
  1. Determines whether the currently-authenticating user still needs to
     link a game account (e.g. no `bohemiaId` user attribute set yet).
  2. If so, renders a form prompting for a PIN/code.
  3. On submit, calls the Central API (server-to-server, this provider's
     own trusted credentials — never the end user's) to validate/redeem
     the code against **the specific Keycloak user id currently
     authenticating** (see the contract note in §4 — this is the one part
     of the existing Central API design that does *not* yet cover this
     caller shape and needs a small addition on the Central API side,
     tracked in the companion eliferpg-side plan referenced in §9).
  4. On success, completes the required action (and may set a Keycloak
     user attribute, e.g. `bohemiaId`, for future `evaluateTriggers`
     checks) and lets Keycloak continue issuing tokens normally.
  5. On failure (invalid code, expired code, network/timeout error to the
     Central API), re-challenges with a clear, distinct error message per
     failure kind — never hang or silently fail the login.
- Package the provider as a JAR deployable into
  `/opt/keycloak/providers/` of a `quay.io/keycloak/keycloak:26.0`-based
  image.
- Produce a `Dockerfile` that layers the JAR onto that base image.

## 4. Non-goals

- **Do not implement PIN generation, the `LinkingCode` store, or the
  account-merge logic.** All of that lives in eliferpg's own Central API
  codebase (`Accounts.Domain`/`Accounts.Application`), per its own,
  separately-tracked implementation plan. This SPI is a **caller** of that
  logic via HTTP, nothing more. Do not reimplement any of it in Java —
  that was explicitly identified as a bad outcome (two independent
  implementations of the same business rule, in two languages, silently
  drifting apart) when this approach was designed. If the Central API
  endpoint this provider needs doesn't exist yet, build against a
  documented contract (§5.2) and coordinate — don't invent your own
  verification logic as a stopgap.
- **Do not implement Discord identity-provider registration.** That's
  realm config (`eliferpg-realm.json`), owned by the eliferpg `backend`
  repo, independent of this SPI.
- **Do not build a `kc.sh build`-optimized production image pipeline.** A
  basic `Dockerfile` layering the JAR onto the stock dev image is
  sufficient for now (matches the realm's current `start-dev` mode, which
  hot-loads providers). If eliferpg moves to a production Keycloak
  deployment later, that's separate follow-up work, not part of this plan.
- **Do not touch the realm JSON, `compose.yml`, or any other file in the
  eliferpg workspace.** Those changes are tracked in
  `../backend/docs/superpowers/plans/2026-08-20-keycloak-spi-integration.md`
  and are someone else's (or a later) task, coordinated against this
  project's outputs (the JAR/image, and the contract in §5.2).

## 5. Integration contract — read this section carefully

This is the part most likely to cause integration bugs if guessed instead
of confirmed. Two things need to be nailed down and agreed with whoever
owns the Central API side before this is considered done.

### 5.1 Authenticating to the Central API

This provider runs **server-side, inside the Keycloak JVM process**. It
must never accept or forward an end-user credential to authenticate its
own calls — it authenticates as *itself*, as a trusted backend service,
using OAuth2 Client Credentials grant against Keycloak's own token
endpoint:

- New Keycloak client (to be added to `eliferpg-realm.json` by the
  eliferpg side): suggested id `keycloak-spi`, grant type Client
  Credentials, narrowly scoped — e.g. a new scope
  `accounts:links:redeem-trusted`, granted **only** to this client, never
  to a Bridge or portal client. Client secret supplied to this provider via
  environment variable (see §8).
- The provider fetches (and caches/refreshes — client-credentials tokens
  are typically short-lived, cache until near expiry then refetch, don't
  fetch per-request) a token from
  `{KEYCLOAK_INTERNAL_URL}/realms/eliferpg/protocol/openid-connect/token`
  using `grant_type=client_credentials` + its own client id/secret, then
  presents that as a Bearer token when calling the Central API.

### 5.2 The redeem call itself — a contract gap to flag, not assume

eliferpg's existing linking design (`2026-08-14-gameserver-web-identity-linking-design.md`,
Part 2) already specifies `POST /api/accounts/links/redeem`, but for
**two** caller shapes, neither of which matches this provider:

- Bridge-initiated: `{BohemiaId, Code}`, authenticated as the Bridge's own
  client-credentials.
- Portal-initiated: `{Code}` only, authenticated as **the player's own
  bearer token** — the target account is resolved from that token's `sub`.

This SPI is a **third shape**: it runs during the Keycloak login flow
itself, before/while the user is authenticating — it has direct access to
the Keycloak user object being processed (`RequiredActionContext.getUser()`,
giving you that user's Keycloak user id) but it does **not** hold a
player-issued Account access token to present as proof of identity (the
whole point of a required action is that it runs mid-authentication). So
the redeem call this provider makes needs an explicit third variant:

```
POST /api/accounts/links/redeem
Authorization: Bearer <keycloak-spi client-credentials token>
Content-Type: application/json

{
  "keycloakUserId": "<the Keycloak user id being authenticated>",
  "code": "<the PIN the user typed>"
}
```

Expected response shape (propose this if not already implemented; mirror
the existing three-way branch from the spec):

```
200 OK
{ "outcome": "linked" | "already-linked" | "merged" }

404 or 410 (ProblemDetails)
{ ... }   // unknown or expired code
```

**This variant is new work on the Central API side that the existing spec
does not cover as written** — its `links/redeem` handler currently only
knows how to resolve a target account from a Bridge-supplied `BohemiaId` or
from a bearer token's own `sub`. Accepting an explicit `keycloakUserId`
from a *trusted system caller* (authorized by client identity/scope, not by
token subject) is a distinct, narrower trust decision — it must be
restricted so **only** the `keycloak-spi` client (never a portal or Bridge
client) can pass an arbitrary `keycloakUserId`. Flag this explicitly when
coordinating with whoever implements the Central API side — do not assume
the plain `{Code}` portal shape is reusable here, since this provider has
no player bearer token to send.

**Action if the endpoint doesn't exist yet when you start:** build against
this contract, write your HTTP client and error-handling logic against it,
and use a local stub/mock server for testing (§7). Do not block on the
Central API team/process; coordinate the contract, not the timeline.

## 6. Project scaffolding

- Build tool: **Maven** (more precedent/examples in the Keycloak SPI
  ecosystem than Gradle; pick Gradle only if you have a strong reason not
  reflected here).
- Suggested coordinates: groupId `net.eliferpg`, artifactId
  `keycloak-eliferpg-spi`, package root `net.eliferpg.keycloak.spi`.
- Dependencies (all `provided` scope — they're supplied by the Keycloak
  runtime, must not be shaded into the JAR):
  - `org.keycloak:keycloak-server-spi`
  - `org.keycloak:keycloak-server-spi-private`
  - `org.keycloak:keycloak-services`
  - all pinned to version **26.0.8** exactly (match the running image;
    do not float a version range).
- HTTP client: use Java's built-in `java.net.http.HttpClient` (Java 11+) —
  avoid pulling in an HTTP client dependency Keycloak doesn't already
  provide, to minimize classpath conflict risk inside Keycloak's own
  classloader.
- JSON (de)serialization: Jackson — already a transitive dependency of
  Keycloak itself; do not add a second JSON library.
- **JDK version — verify, don't assume.** Confirm the JDK version the
  running `quay.io/keycloak/keycloak:26.0.8` container actually uses (e.g.
  `docker exec <container> java -version` against a live instance) before
  finalizing `maven.compiler.release`. Public Keycloak 26 guidance points
  at JDK 17+, but this workspace's own convention (see
  `../backend/ARCHITECTURE.md` §4.3, "Verified against Keycloak 26.0.8") is
  to confirm against the live instance rather than trust documentation
  alone — do the same here and record what you found.
- Directory layout:
  ```
  pom.xml
  src/main/java/net/eliferpg/keycloak/spi/
    LinkGameAccountRequiredActionFactory.java
    LinkGameAccountRequiredAction.java
    CentralApiClient.java          (client-credentials fetch + redeem call)
    CentralApiClientException.java (typed failure: invalid, expired, network)
  src/main/resources/
    META-INF/services/org.keycloak.authentication.RequiredActionFactory
    theme/eliferpg/login/link-game-account.ftl   (or reuse base theme + a
      new template only if the base "form" template isn't sufficient)
  src/test/java/...                (unit tests, §7)
  Dockerfile
  ```

## 7. Implementation details

### 7.1 `LinkGameAccountRequiredActionFactory`

Implements `org.keycloak.authentication.RequiredActionFactory`. Provides a
stable `getId()` (e.g. `"link-game-account"` — this string is what
`eliferpg-realm.json` will reference when wiring the flow, so pick it
deliberately and document it prominently in this repo's README once
written), `create(KeycloakSession)` returning the provider instance,
standard no-op `init`/`postInit`/`close`.

### 7.2 `LinkGameAccountRequiredAction` implements `RequiredActionProvider`

- **`getDisplayText()`** — human-readable label shown in Keycloak's admin
  console required-actions list, e.g. `"Link Game Account"`.
- **`evaluateTriggers(RequiredActionContext context)`** — decide whether
  this action should be added to the current user's required actions for
  this session. Suggested check: read a Keycloak user attribute (e.g.
  `bohemiaId`) via `context.getUser().getFirstAttribute("bohemiaId")`; if
  absent/blank, call `context.getUser().addRequiredAction(PROVIDER_ID)`.
  This method runs on every login, so keep it cheap — attribute read only,
  no network call here.
- **`requiredActionChallenge(RequiredActionContext context)`** — render
  the PIN entry form: `context.challenge(context.form().createForm("link-game-account.ftl"))`.
  Keep the form minimal: one text input for the code, a submit button, and
  a place for an error message driven by a form attribute (see below).
- **`processAction(RequiredActionContext context)`** — handle form
  submission:
  1. Read the submitted code from
     `context.getHttpRequest().getDecodedFormParameters()`.
  2. Basic input validation (non-blank, reasonable length/charset) before
     making any network call.
  3. Call `CentralApiClient.redeem(keycloakUserId, code)` (§7.3).
  4. On `linked`/`already-linked`/`merged` outcomes: optionally set the
     `bohemiaId` (or equivalent confirmation) attribute on
     `context.getUser()`, then `context.success()`.
  5. On `invalid`/`expired`: re-render the challenge with a specific,
     user-facing error message (distinguish "wrong code" from "code
     expired, generate a new one" if the Central API's response
     distinguishes them — don't collapse both into one generic message).
  6. On network error/timeout/5xx from the Central API: re-render with a
     generic "something went wrong, try again" message; **never** let an
     exception here crash the login flow uncaught — catch broadly around
     the HTTP call specifically and convert to a challenge response.
  7. Apply a request timeout on the HTTP call (e.g. 5s) so a slow/hung
     Central API cannot indefinitely stall someone's login.
- **`close()`** — no-op unless you hold a resource that needs releasing.

### 7.3 `CentralApiClient`

- Holds: Central API base URL, `keycloak-spi` client id/secret, Keycloak's
  own token endpoint URL (all from config, §8).
- `fetchClientCredentialsToken()` — POST to Keycloak's token endpoint,
  cache the token + expiry in memory (a simple synchronized field is
  sufficient; this runs inside Keycloak's own process so don't add
  external caching infrastructure), refetch when within some margin (e.g.
  30s) of expiry.
- `redeem(String keycloakUserId, String code)` — POST to
  `{centralApiBaseUrl}/api/accounts/links/redeem` per the contract in
  §5.2, with the cached bearer token. Return a typed result (e.g. a sealed
  outcome type / enum matching `linked | already-linked | merged | invalid
  | expired | error`) rather than leaking raw HTTP status codes into the
  required-action logic.

## 8. Configuration

All via environment variables (matches this workspace's general convention
of env-var-driven config, not hardcoded values):

| Variable | Purpose |
|---|---|
| `ELIFERPG_CENTRAL_API_BASE_URL` | Base URL of the Central API, reachable from inside the Keycloak container's network |
| `ELIFERPG_KEYCLOAK_SPI_CLIENT_ID` | e.g. `keycloak-spi` |
| `ELIFERPG_KEYCLOAK_SPI_CLIENT_SECRET` | Secret for the above client |
| `ELIFERPG_KEYCLOAK_TOKEN_URL` | Usually derivable from Keycloak's own `KC_HOSTNAME`/realm, but make it explicit/overridable rather than assumed, since this runs inside the same process issuing that config |

Read these via `System.getenv(...)` at provider init/first use; document
defaults (or explicit failure if unset) clearly — don't silently fall back
to a guessed URL.

## 9. Packaging & deployment

- `Dockerfile`:
  ```dockerfile
  FROM quay.io/keycloak/keycloak:26.0
  COPY target/keycloak-eliferpg-spi-*.jar /opt/keycloak/providers/
  COPY src/main/resources/theme/eliferpg /opt/keycloak/themes/eliferpg
  ```
  (theme copy only needed if a custom FTL template/theme dir is used —
  omit if the default base theme's form template is sufficient and you're
  only adding a new required-action id, not new markup.)
- Build: `mvn clean package` → `docker build -t eliferpg/keycloak-spi:<tag> .`
- **How this plugs into eliferpg's `compose.yml` is not this repo's
  concern** — that's tracked in
  `../backend/docs/superpowers/plans/2026-08-20-keycloak-spi-integration.md`.
  This repo's responsibility ends at "produces a working image others can
  reference."

## 10. Testing

- **Unit tests**: mock `CentralApiClient`'s HTTP layer (e.g. via a small
  seam — inject an `HttpClient` or wrap the call behind an interface) and
  test `processAction`'s branching (success/invalid/expired/network-error)
  without a real Keycloak or Central API running.
- **Manual integration walkthrough** (document exact steps once you've
  run them, in a `TESTING.md` or this file's appendix):
  1. Build the image, run it standalone (or via a local Keycloak +
     mounted `/opt/keycloak/providers`) against a stub Central API (a
     trivial local HTTP server returning canned `linked`/`invalid`
     responses is enough — you don't need the real Central API for this).
  2. Confirm the required action appears in Keycloak's admin console
     under Authentication → Required Actions, and can be enabled.
  3. Trigger it end-to-end: log in as a test user with the required action
     assigned, confirm the PIN form renders, submit a code, confirm both
     the success and failure paths behave as designed.
  4. Confirm a slow/unreachable Central API times out and re-challenges
     rather than hanging.
- No CI exists yet in this repo (it's brand new) or in the eliferpg
  `backend` repo. Recommend a minimal `mvn verify` GitHub Actions workflow
  here as a starting point; image build/publish automation can follow once
  there's an actual deployment target for it.

## 11. Versioning & compatibility risk — be explicit about this, don't bury it

`RequiredActionProvider` is part of Keycloak's SPI surface, which does
**not** carry a cross-version compatibility guarantee (this is the exact
concern that led the eliferpg team to reject a *different* custom SPI for
an unrelated problem — see `../backend/ARCHITECTURE.md` §4.3 for the full
reasoning if you want the precedent). Concretely:

- Pin the Keycloak SPI dependency versions to **26.0.8** exactly.
- Any future bump of eliferpg's Keycloak version (`compose.yml`'s
  `quay.io/keycloak/keycloak:26.0` tag) requires rebuilding and
  re-verifying this provider against the new version before that bump
  ships — it is not safe to assume forward compatibility. Document this
  as a standing operational note in this repo's README once written.

## 12. Deliverables checklist

- [ ] Maven project builds `keycloak-eliferpg-spi-<version>.jar`
- [ ] `RequiredActionFactory`/`RequiredActionProvider` implemented per §7
- [ ] `CentralApiClient` implemented against the contract in §5.2, with
      client-credentials token caching
- [ ] PIN entry form renders and submits correctly
- [ ] Success, invalid-code, expired-code, and network-error paths all
      produce distinct, correct user-facing outcomes
- [ ] `Dockerfile` produces a working image
- [ ] Unit tests for the branching logic in `processAction`
- [ ] Manual integration walkthrough performed and documented
- [ ] JDK version requirement verified against the live 26.0.8 container,
      not assumed from documentation
- [ ] README written covering: the `link-game-account` provider id (for
      realm-config wiring elsewhere), the env vars from §8, the versioning
      note from §11, and a pointer back to
      `../backend/docs/superpowers/plans/2026-08-20-keycloak-spi-integration.md`
      for how this plugs into the rest of the system
