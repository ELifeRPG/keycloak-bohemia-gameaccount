# keycloak-bohemia-gameaccount

A custom Keycloak **Required Action Provider** for the `eliferpg` realm — a
game backend for a custom ArmA Reforger mod ("eLifeRPG"). It binds a player's
in-game Bohemia identity to their **already-existing** Keycloak account.

The player's Keycloak user is created by ordinary web signup (Discord broker or
username/password) — this SPI never creates one. When an unlinked player joins
the gameserver, a trusted server-to-server caller mints a short **PIN** through
this provider; the game displays it, and the player types it into Keycloak's own
form. On success the provider writes a `bohemiaId` attribute onto the
already-authenticated user. That is the entire operation: **one user, one
attribute write** — no second account, no merge, no user deletion.

Linking happens entirely against the local Keycloak session; this repo makes no
outbound HTTP calls of its own.

This README covers what's needed to build, run, and integrate this repo; see
the "REST endpoint contracts" section below for the current API surface.

Two older documents are kept for history and are **not** the current contract:
[`IMPLEMENTATION_PLAN.md`](./IMPLEMENTATION_PLAN.md) (describes a retired Central
API integration, though its `bohemiaId`-attribute design is what the provider
now actually does), and [`TESTING.md`](./TESTING.md), whose walkthrough captures
the since-removed two-user merge flow.

## Provider IDs

This jar registers two providers, under two deliberately different ids:

| Provider | Id | Where the id shows up |
| --- | --- | --- |
| `RequiredActionFactory` | **`link-bohemia-gameaccount`** | `kc_action=link-bohemia-gameaccount`, and the required-action `alias`/`providerId` in realm config |
| `RealmResourceProviderFactory` | **`bohemia-gameaccount`** | the REST path segment, `/realms/{realm}/bohemia-gameaccount/...` |

The action id reads as a verb because that is how a caller uses it; the resource
id is a noun namespace, so further endpoints can hang off it without the path
reading as an action.

Both are referenced by `eliferpg-realm.json` (in the sibling `backend` repo), which now
wires this provider into the realm: the required action, the
`accounts:bohemia-gameaccount:manage` scope granted to `account-service`, and a
`bohemia-id` client scope carrying the `bohemia_id` claim mapper. Keep them in step with
that repo — they are declared here as
`LinkBohemiaGameAccountRequiredActionFactory.PROVIDER_ID` and
`BohemiaGameAccountResourceFactory.ID`.

Because the realm depends on this provider, `backend/compose.yml` runs the image built
from this repo rather than stock Keycloak.

## Building

```bash
docker run --rm -v "$(pwd)":/app -w /app -v keycloak-bohemia-gameaccount-m2:/root/.m2 maven:3.9-eclipse-temurin-21 mvn clean package
docker build -t eliferpg/keycloak-bohemia-gameaccount:<tag> .
```

The first command builds `target/keycloak-bohemia-gameaccount-<version>.jar`
against the Maven `.m2` cache volume. The second layers that jar (plus this
plugin's own `eliferpg-reforger` login theme — a bare `parent=keycloak` theme
that exists only to carry `link-bohemia-gameaccount.ftl`) onto the
`quay.io/keycloak/keycloak:26.0` base image per the repo's `Dockerfile`. See
"Wiring this into a realm" below for how that relates to the separate,
styled `eliferpg` theme used in production.

## Development

This repo includes a `.devcontainer/` matching the convention used across
the rest of the `eliferpg` workspace's repos. Opening the repo in VS Code
(or any Dev Containers-compatible tool) with the Dev Containers extension
gets you a ready-to-use environment with Java 21 and Maven directly on
`PATH`, plus Docker-outside-of-Docker access (this repo, unlike its
siblings, needs to build and run Docker images as part of its own normal
workflow — hence the extra `docker-outside-of-docker` feature). Inside the
devcontainer, `mvn`, `docker`, and `docker compose` all work directly,
without the `docker run ... maven:3.9-eclipse-temurin-21 ...` wrapper shown
above.

**Note on ports:** containers you start from inside the devcontainer via
`docker`/`docker compose` (e.g. `./scripts/local-dev-up.sh` or
`docker compose -f docker-compose.integration-test.yml up`) are *sibling*
containers on the host's real Docker daemon (that's what
docker-outside-of-docker means) — not containers nested inside the
devcontainer itself, and not reachable via `localhost` from inside the
devcontainer's own shell on their own (confirmed directly: a plain
`curl localhost:18080` run inside the devcontainer fails to connect, even
though the same port is reachable from the bare host via
`host.docker.internal`).

**This is handled automatically as of this devcontainer's current config**:
`.devcontainer/start-port-proxies.sh` runs as a `postStartCommand` and
relays `localhost:18080`/`localhost:18082` (this repo's two fixed local-stack
ports) inside the devcontainer to `host.docker.internal`, using `socat`
(already present in the base image). That makes them genuinely local ports
as far as the devcontainer's own network namespace is concerned — so
`curl localhost:18080` from a shell in here just works, and (per
`forwardPorts`/`portsAttributes` in `devcontainer.json`) VS Code has a real
local listener to forward, both automatically and via the Ports panel,
rather than needing to reach across to a sibling container the way it
couldn't before. Verified directly: `curl localhost:18080` from inside the
devcontainer succeeds once `docker compose up` has started Keycloak,
confirmed idempotent (`start-port-proxies.sh` can be re-run safely, e.g. on
every VS Code reattach, without erroring or double-binding).

If you're on an older checkout of this devcontainer (before this fix) or
something about your setup still doesn't pick it up, the manual fallbacks
that were confirmed to work in practice are: connect directly to the host's
network IP instead of `localhost` (`http://<host-network-ip>:18080`, since
the port is genuinely bound on `0.0.0.0` on the real host and reachable
that way as long as your machine can route to the host, e.g. same LAN or
VPN), or a manual SSH tunnel (`ssh -L 18080:localhost:18080 <remote-host>`)
if you'd rather keep using `localhost` on your own machine. VS Code's own
manual "Forward a Port" (Ports panel) was confirmed *not* to reliably reach
a port only bound on a sibling container, at least on one real Remote-SSH +
Dev Containers setup — which is exactly why this devcontainer now avoids
needing that path at all rather than relying on it.

## Trying it yourself

Two different local stacks exist, for two different purposes:

- **`./scripts/local-dev-up.sh`** (backed by `compose.yml`) — a convenience
  stack for casual, manual, click-through testing. It builds and starts Keycloak
  (with this provider loaded), bootstraps a realm, a browser client, a test user,
  a confidential service-account client holding the trusted scope, and the
  `bohemia_id` protocol mapper. It then mints a demo PIN **through the real
  endpoint** rather than faking one, and **leaves the stack running**. When it
  finishes it prints two URLs: a plain login (which completes without ever
  prompting — proving the action does not interrupt on its own) and the same
  login plus `kc_action=link-bohemia-gameaccount` (which is what the portal's "Link Bohemia game
  account" button does), along with the PIN to type in. Tear down with
  `docker compose down -v`.
- **`./scripts/integration-test.sh`** (backed by `docker-compose.integration-test.yml`)
  — a headless, scripted, end-to-end regression run that builds the provider
  image, drives five cases via curl, asserts on each, and tears itself down.
  The cases are: success (asserting the `bohemiaId` attribute actually persisted,
  not merely that a page rendered), invalid PIN, **replayed PIN** (the
  consume-once regression), conflict setup, and conflict rejected.

  Note the explicit `docker build` step: `docker-compose.integration-test.yml`
  pins a prebuilt `:dev` image rather than a build context, so `up --build` is a
  no-op for it. Without that step the suite silently runs against whatever `:dev`
  image is lying around — which is exactly how it was found to be testing a
  weeks-old jar.

## Wiring this into a realm

This repo builds and packages the provider; actually enabling it for a realm is
realm-config work (out of scope here), but five things a downstream consumer
needs to know are consolidated here.

1. **The realm's `loginTheme` must resolve `link-bohemia-gameaccount.ftl`.**
   Without it the realm falls back to Keycloak's default theme, which has no
   such template, and the challenge page 500s with
   `freemarker.template.TemplateNotFoundException`.

   This jar packages its own bare theme, **`eliferpg-reforger`**
   (`parent=keycloak`), that carries only that one template — set
   `loginTheme=eliferpg-reforger` directly for a standalone/local deploy of
   this repo alone (this is what `scripts/local-dev-up.sh` and
   `scripts/integration-test.sh` do).

   Production instead uses the styled theme from the separate
   `keycloak-theme-eliferpg` repo, registered under the name **`eliferpg`**.
   That repo's login `theme.properties` sets `parent=eliferpg-reforger`, so
   with both jars deployed, `loginTheme=eliferpg` renders every page styled
   and still resolves `link-bohemia-gameaccount.ftl` (and its own
   `template.ftl` shell) via the parent chain. The two jars must use
   different theme names — Keycloak resolves a theme by `(name, type)` to a
   single provider, so a jar named the same as another `login` theme would
   silently hide it rather than merge with it.
2. **Registering the required action is two Admin REST API calls, not one.**
   `POST /admin/realms/{realm}/authentication/register-required-action` (with
   `{"providerId":"link-bohemia-gameaccount","name":"Link Bohemia Game Account"}`) must run
   first — it creates the realm's `RequiredActionProviderModel` row. Only then
   does `PUT /admin/realms/{realm}/authentication/required-actions/link-bohemia-gameaccount`
   succeed; called alone and first it 404s with
   `{"error":"Failed to find required action"}`. Register it with
   **`defaultAction: false`**.
3. **The action is application-initiated, and never fires on its own.**
   `evaluateTriggers` deliberately does nothing, and the provider reports
   `InitiatedActionSupport.SUPPORTED`. The portal brings a player to the form by
   adding `kc_action=link-bohemia-gameaccount` to its authorization request; Keycloak
   reports the result back on the redirect as `kc_action_status=success`.

   This is not a detail — it is load-bearing. A player who signs up on the portal
   to submit a whitelist application has not joined the gameserver yet and so has
   no PIN. If the action added itself at login, that player would be stranded at
   a prompt they cannot satisfy. (This also retires the realm-wide stranding
   footgun that earlier versions of this README warned about.)
4. **The provider writes a `bohemiaId` user attribute.** Expose it to clients
   with a **stock** User Attribute protocol mapper (`oidc-usermodel-attribute-mapper`,
   user attribute `bohemiaId` → claim `bohemia_id`); no custom mapper SPI is
   needed. Note that Keycloak 26's declarative User Profile hides unmanaged
   attributes from Admin REST API *reads*, so set `unmanagedAttributePolicy` (or
   declare `bohemiaId` in the profile) if you want to inspect it via the Admin
   API — the provider's own write bypasses User Profile and works regardless.
5. **The `accounts:bohemia-gameaccount:manage` client scope must never be assigned to a
   public or user-facing client.** It permits minting a PIN for an arbitrary
   `bohemiaId`, which is enough to claim someone else's game identity. Grant it
   only to confidential, service-account-enabled clients.

## Versioning & compatibility risk

`RequiredActionProvider` is part of Keycloak's SPI surface, which does
**not** carry a cross-version compatibility guarantee. This provider is
pinned and verified against Keycloak **26.0.8** specifically (see
`pom.xml`'s `keycloak.version` property, and the JDK verification note
below). **Any future bump of eliferpg's Keycloak version requires rebuilding
and re-verifying this provider against the new version first** — forward
compatibility is not safe to assume.

## JDK version

Public Keycloak 26 guidance points at JDK 17+, but per this workspace's
convention of confirming against the live instance rather than trusting
documentation alone, the JDK actually shipped in the target image was
verified directly:

```
$ docker run --rm --entrypoint java quay.io/keycloak/keycloak:26.0 -version
openjdk version "21.0.6" 2025-01-21 LTS
```

`maven.compiler.release` is set to `21` accordingly.

## REST endpoint contracts

This provider registers a `RealmResourceProvider` (id `bohemia-gameaccount`)
exposing two endpoints under every realm it's enabled for:

```
POST /realms/{realm}/bohemia-gameaccount/pin
GET  /realms/{realm}/bohemia-gameaccount/status?bohemiaId=<id>
```

**Both are trusted-caller only.** They require a valid bearer token whose subject
is a service account (`getServiceAccountClientLink() != null`) *and* whose `scope`
claim contains `accounts:bohemia-gameaccount:manage`. A missing/invalid token gets
`401 {"error":"unauthenticated"}`; an authenticated but non-trusted caller gets
`403 {"error":"trusted scope required"}`.

There is deliberately **no self-service redemption endpoint**. A player redeems a
PIN by typing it into Keycloak's own form (the `link-bohemia-gameaccount` required
action), never by calling an API — so no token a player holds can bind a game
identity to their account without going through the login flow.

**`POST .../pin`** — mints a PIN for a Bohemia ID that has no Keycloak user bound
to it yet. Request body: `{ "bohemiaId": "<id>" }`.

```
200 OK   { "pin": "<the PIN>", "expiresInSeconds": 1800 }
400      { "error": "bohemiaId required" }
403      { "error": "trusted scope required" }
409      { "error": "already-linked", "keycloakUserId": "<uuid>" }
```

The `409` matters: once a `bohemiaId` is bound, minting for it is refused. That is
the first line of defence against binding one game identity to two accounts.

**`GET .../status`** — resolves whether a Bohemia ID is bound, and to whom. This is
how a backend maps `bohemiaId` → Keycloak user without being granted
`realm-management: view-users`.

```
200 OK   { "linked": false }
200 OK   { "linked": true, "keycloakUserId": "<uuid>" }
400      { "error": "bohemiaId required" }
403      { "error": "trusted scope required" }
```

### PIN lifecycle

A PIN is minted **when an unlinked player joins the gameserver** — the backend
looks up the `bohemiaId`, finds nothing bound to it, and calls `POST .../pin`. The
TTL starts there. It does *not* have to span the gap between portal signup and
first join, which can be days: that gap is bridged by the permanent `bohemiaId`
attribute, and no PIN exists during it. Every join by a still-unlinked player mints
a fresh one; they are independent single-use keys, so several can be live at once
without conflicting, and unconsumed ones simply expire.

The portal cannot mint a PIN itself, by design — for an unlinked player it has no
idea which Bohemia ID they are. That is why the PIN flows game → player → portal
and never the other way.

PINs are held in Keycloak's `SingleUseObjectProvider` with a 1800s (30 minute) TTL,
never on a user. `remove()` returns the previous value, which makes consumption atomic — two
concurrent redemptions of the same PIN cannot both succeed. One consequence worth
knowing: an **expired PIN is indistinguishable from an invalid one**, because
nothing is left behind to report on. That is deliberate as well as unavoidable —
distinguishing them would hand out a PIN-probing oracle.

## How this fits into the rest of eliferpg

Wiring this provider's image into eliferpg's realm config and `compose.yml`
is explicitly out of scope for this repo. See
`../backend/docs/superpowers/plans/2026-08-20-keycloak-spi-integration.md`
in the sibling `backend` repo for that work.
