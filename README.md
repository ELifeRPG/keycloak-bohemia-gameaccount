# keycloak-bohemia-gameaccount

A Keycloak extension that binds a player's in-game **Bohemia identity** to their existing
Keycloak account, for the `eliferpg` realm behind a custom ArmA Reforger mod.

The player's Keycloak user comes from ordinary web signup — Discord broker or
username/password — and this plugin never creates one. When an unlinked player joins the
gameserver, a trusted server-to-server caller mints a short **PIN** through this plugin;
the game displays it, and the player types it into Keycloak's own login form. On success
the plugin writes a `bohemiaId` attribute onto the already-authenticated user.

That is the whole operation: **one user, one attribute write.** No second account, no
merge, no user deletion. Linking runs entirely against the local Keycloak session — the
plugin makes no outbound HTTP calls of its own.

```
1.  portal signup        →  Keycloak user exists, with no bohemiaId
2.  player joins         →  backend finds no account for that Bohemia ID,
                            calls POST …/bohemia-gameaccount/pin
3.  mod displays the PIN →  player enters it in Keycloak's login form
4.  this plugin writes bohemiaId onto that user — permanently
```

Steps 1 and 2 can be days apart. Nothing links them until step 4.

## Requirements

| | |
| --- | --- |
| Keycloak | **26.0.8** (see [Compatibility](#compatibility)) |
| Java | 21 — the JDK shipped in `quay.io/keycloak/keycloak:26.0` |
| Build | Maven 3.9 |

## Installing

Every [release](https://github.com/ELifeRPG/keycloak-bohemia-gameaccount/releases/latest)
publishes both a jar and a container image. Either use the image:

```dockerfile
FROM ghcr.io/eliferpg/keycloak-bohemia-gameaccount:<tag>
```

…or layer the jar onto a Keycloak image yourself:

```dockerfile
FROM quay.io/keycloak/keycloak:26.0
COPY keycloak-bohemia-gameaccount-<version>.jar /opt/keycloak/providers/
```

The jar is all that is needed: it registers both providers via `META-INF/services`, and
ships the `eliferpg-reforger` login theme inside itself via `META-INF/keycloak-themes.json`.

Then configure the realm — see [Realm configuration](#realm-configuration).

## Provider IDs

The jar registers two providers, under two deliberately different ids:

| Provider | Id | Where it shows up |
| --- | --- | --- |
| `RequiredActionFactory` | **`link-bohemia-gameaccount`** | `kc_action=link-bohemia-gameaccount`; the required-action `alias`/`providerId` in realm config |
| `RealmResourceProviderFactory` | **`bohemia-gameaccount`** | the REST path segment, `/realms/{realm}/bohemia-gameaccount/…` |

The action id is a verb because that is how a caller uses it; the resource id is a noun
namespace, so further endpoints can hang off it without the path reading as an action.
They are declared as `LinkBohemiaGameAccountRequiredActionFactory.PROVIDER_ID` and
`BohemiaGameAccountResourceFactory.ID`.

## Realm configuration

**1. `loginTheme` must resolve `link-bohemia-gameaccount.ftl`.** Otherwise the challenge
page fails with `freemarker.template.TemplateNotFoundException` (HTTP 500) — Keycloak's
default theme has no such template.

This jar packages a bare theme, **`eliferpg-reforger`** (`parent=keycloak`), carrying only
that template. Set `loginTheme=eliferpg-reforger` for a standalone deploy of this plugin
alone.

For a styled deployment, the separate `keycloak-theme-eliferpg` repo registers a theme
named **`eliferpg`** whose `parent=eliferpg-reforger`; with both jars deployed,
`loginTheme=eliferpg` renders every page styled and still resolves this template through
the parent chain. The two theme names must differ — Keycloak resolves a theme by
`(name, type)` to a single provider, so identically named `login` themes hide one another
rather than merging.

**2. Registering the required action takes two Admin API calls, in order.**

```http
POST /admin/realms/{realm}/authentication/register-required-action
     {"providerId":"link-bohemia-gameaccount","name":"Link Bohemia Game Account"}

PUT  /admin/realms/{realm}/authentication/required-actions/link-bohemia-gameaccount
     {"alias":"link-bohemia-gameaccount", …, "enabled":true, "defaultAction":false}
```

The `POST` creates the realm's `RequiredActionProviderModel` row; the `PUT` alone, first,
returns `404 {"error":"Failed to find required action"}`. Register it with
**`defaultAction: false`**.

**3. The action is application-initiated and never fires on its own.** `evaluateTriggers`
does nothing, and the provider reports `InitiatedActionSupport.SUPPORTED`. A caller brings
a player to the form by adding `kc_action=link-bohemia-gameaccount` to its authorization
request; Keycloak reports the outcome back on the redirect as `kc_action_status=success`.

This is load-bearing, not incidental. A player who signs up on the portal has not joined
the gameserver yet and has no PIN — an action that added itself at login would strand them
at a prompt they cannot satisfy.

**4. Expose the attribute with a stock protocol mapper.** The plugin writes a `bohemiaId`
user attribute; map it with `oidc-usermodel-attribute-mapper` (`bohemiaId` → claim
`bohemia_id`). No custom mapper is needed.

Keycloak 26's declarative User Profile hides unmanaged attributes from Admin API *reads*,
so set `unmanagedAttributePolicy` (or declare `bohemiaId` in the profile) if you want to
inspect it there. The plugin's own write bypasses User Profile and works regardless.

**5. Never grant `accounts:bohemia-gameaccount:manage` to a public or user-facing client.**
It permits minting a PIN for an arbitrary `bohemiaId`, which is enough to claim someone
else's game identity. Grant it only to confidential, service-account-enabled clients.

## REST API

Two endpoints, exposed under every realm the provider is enabled for:

```
POST /realms/{realm}/bohemia-gameaccount/pin
GET  /realms/{realm}/bohemia-gameaccount/status?bohemiaId=<id>
```

**Both are trusted-caller only.** They require a bearer token whose subject is a service
account (`getServiceAccountClientLink() != null`) *and* whose `scope` claim contains
`accounts:bohemia-gameaccount:manage`. A missing or invalid token gets
`401 {"error":"unauthenticated"}`; an authenticated but non-trusted caller gets
`403 {"error":"trusted scope required"}`.

There is deliberately **no self-service redemption endpoint**. A player redeems a PIN only
by typing it into Keycloak's own form, so no token a player holds can bind a game identity
to their account outside the login flow.

### `POST …/pin`

Mints a PIN for a Bohemia ID no Keycloak user is bound to yet.
Request body: `{ "bohemiaId": "<id>" }`.

```
200  { "pin": "<the PIN>", "expiresInSeconds": 1800 }
400  { "error": "bohemiaId required" }
403  { "error": "trusted scope required" }
409  { "error": "already-linked", "keycloakUserId": "<uuid>" }
```

The `409` is the first line of defence against binding one game identity to two accounts:
once a `bohemiaId` is bound, minting for it is refused.

### `GET …/status`

Resolves whether a Bohemia ID is bound, and to whom — so a backend can map
`bohemiaId` → Keycloak user without being granted `realm-management: view-users`.

```
200  { "linked": false }
200  { "linked": true, "keycloakUserId": "<uuid>" }
400  { "error": "bohemiaId required" }
403  { "error": "trusted scope required" }
```

## PIN behaviour

A PIN is minted **when an unlinked player joins the gameserver**, and the TTL starts
there. It does not have to span the gap between portal signup and first join, which may be
days — that gap is bridged by the permanent `bohemiaId` attribute, and no PIN exists
during it. Every join by a still-unlinked player mints a fresh one; they are independent
single-use keys, so several can be live at once without conflicting, and unconsumed ones
simply expire.

A caller cannot mint a PIN on a player's behalf from the portal side, by design: for an
unlinked player there is no way to know which Bohemia ID they are. The PIN flows
game → player → portal, never the other way.

PINs live in Keycloak's `SingleUseObjectProvider` with a **1800s (30 minute)** TTL, never
on a user. `remove()` returns the previous value, which makes consumption atomic — two
concurrent redemptions of the same PIN cannot both succeed.

Two consequences worth knowing:

- An **expired PIN is indistinguishable from an invalid one**. Nothing is left behind to
  report on, and distinguishing them would hand out a PIN-probing oracle.
- **Uniqueness is enforced by this plugin, not by the database.** Keycloak accepts a
  duplicate binding at write time and only fails later on read, permanently breaking that
  user's login — so `bind()` checks first.

## Building

```bash
mvn clean package
docker build -t eliferpg/keycloak-bohemia-gameaccount:<tag> .
```

Or without a local JDK:

```bash
docker run --rm -v "$(pwd)":/app -w /app \
  -v keycloak-bohemia-gameaccount-m2:/root/.m2 \
  maven:3.9-eclipse-temurin-21 mvn clean package
```

`mvn package` produces `target/keycloak-bohemia-gameaccount-<version>.jar`; the
`Dockerfile` layers that plus the `eliferpg-reforger` theme onto
`quay.io/keycloak/keycloak:26.0`. The tag above is local-only — the published image lives
at `ghcr.io/eliferpg/keycloak-bohemia-gameaccount`.

CI runs `mvn -B verify` on every push and pull request. Publishing a GitHub Release builds
the plugin at that tag's version — tag `v0.2.0` yields
`keycloak-bohemia-gameaccount-0.2.0.jar` — attaches the jar to the release, and pushes the
image as `ghcr.io/eliferpg/keycloak-bohemia-gameaccount:0.2.0`. A release not marked as a
prerelease also moves the `:latest` tag.

## Testing

`mvn verify` runs the unit tests. Two local stacks cover the rest:

**`./scripts/integration-test.sh`** — headless end-to-end regression run. Builds the
provider image, drives five cases via curl, asserts on each, and tears itself down:
success (asserting the `bohemiaId` attribute actually persisted, not merely that a page
rendered), invalid PIN, replayed PIN (the consume-once guarantee), conflict setup, and
conflict rejected.

> The explicit `docker build` step is required: `docker-compose.integration-test.yml` pins
> a prebuilt `:dev` image rather than a build context, so `up --build` is a no-op for it.
> Without the build, the suite runs against whatever `:dev` image is already present.

**`./scripts/local-dev-up.sh`** — click-through stack for manual testing. Starts Keycloak
with the provider loaded and bootstraps a realm, a browser client, a test user, a
confidential service-account client holding the trusted scope, and the `bohemia_id`
protocol mapper. It mints a demo PIN through the real endpoint rather than faking one, and
leaves the stack running, printing two URLs: a plain login (which completes without
prompting, showing the action does not interrupt on its own) and the same login plus
`kc_action=link-bohemia-gameaccount`. Tear down with `docker compose down -v`.

## Development

The repo ships a `.devcontainer/` with Java 21, Maven, and Docker-outside-of-Docker, so
`mvn`, `docker`, and `docker compose` all work directly.

Containers started from inside the devcontainer are *siblings* on the host's Docker
daemon, not nested, so they are not reachable on `localhost` by default.
`.devcontainer/start-port-proxies.sh` runs as a `postStartCommand` and relays the two
fixed local-stack ports (`18080`, `18082`) to `host.docker.internal` using `socat`, which
makes them genuinely local to the devcontainer and forwardable by VS Code. It is
idempotent and safe to re-run on every reattach.

## Compatibility

`RequiredActionProvider` is part of Keycloak's SPI surface, which carries **no
cross-version compatibility guarantee**. This plugin is built and verified against
Keycloak **26.0.8** specifically (`keycloak.version` in `pom.xml`).

Any Keycloak upgrade requires rebuilding and re-verifying this plugin against the new
version first — forward compatibility is not safe to assume.
