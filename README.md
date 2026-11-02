# keycloak-bohemia-gameaccount

A Keycloak extension that binds a player's in-game **Game Identities** to their Keycloak
account, for the `eliferpg` realm behind a custom ArmA Reforger mod.

Arma Reforger mints a **Game Identity per game-platform combination**, so a player on PC,
Xbox and PS5 has three, and none of them is their Bohemia account. This plugin therefore
holds them in a **multi-valued** `gameIdentityIds` attribute and **appends** on each link:
one Keycloak account, any number of game identities.

The player's Keycloak user comes from ordinary web signup — Discord broker or
username/password — and this plugin never creates one. When a player with no linked web
account joins the gameserver, a trusted server-to-server caller mints a short **PIN**
through this plugin. The backend puts that PIN straight on a `kc_action` link URL, so the
player follows the link and finds the form already filled in; they can also type the PIN
by hand if they reached the form another way.

That is the whole operation: **one user, one attribute write.** No second account, no
merge, no user deletion. Linking runs entirely against the local Keycloak session — the
plugin makes no outbound HTTP calls of its own.

```
1.  player joins        →  backend creates the account, mints a PIN via
                           POST …/bohemia-gameaccount/pin, builds a link URL
2.  mod shows the link  →  …/auth?…&kc_action=link-bohemia-gameaccount&link_pin=<PIN>
3.  player signs in     →  the form comes up with the PIN already in it
4.  this plugin appends the Game Identity to gameIdentityIds — permanently
5.  player joins on another platform → repeat; the new identity is ADDED, not swapped
```

The player may have signed up on the portal first instead, in which case step 1 is a join
that finds their existing account. Either order works, and nothing links the two sides
until step 4.

## Requirements

| | |
| --- | --- |
| Keycloak | **26.0.8** (see [Compatibility](#compatibility)) |
| Java | 21 — the JDK shipped in `quay.io/keycloak/keycloak:26.0` |
| Build | Maven 3.9 |

## Installing

Every [release](https://github.com/ELifeRPG/keycloak-bohemia-gameaccount/releases/latest)
attaches the jar. ELifeRPG runs it through
[`ghcr.io/eliferpg/keycloak`](https://github.com/ELifeRPG/keycloak), which bakes in the latest
release of this and the theme plugin. To layer it onto a Keycloak image yourself:

```dockerfile
FROM quay.io/keycloak/keycloak:26.0
COPY keycloak-bohemia-gameaccount-<version>.jar /opt/keycloak/providers/
```

The jar is all that is needed: it registers both providers via `META-INF/services`, and
ships `link-bohemia-gameaccount.ftl` under `theme-resources/templates` — Keycloak's
`ClasspathThemeResourceProviderFactory` makes it available to whatever login theme a
realm uses, no `loginTheme` configuration required.

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

**1. No `loginTheme` configuration is required.** `link-bohemia-gameaccount.ftl` ships
under `theme-resources/templates` in this jar (see "Installing" above), which Keycloak
makes available to whatever theme is active for the realm — the default theme, this
plugin deployed alone, or a styled theme like the separate `keycloak-theme-eliferpg`
repo's `eliferpg`, all render it correctly with zero coordination between jars. This
is also how multiple SPI plugins with their own UI can coexist: each just ships its
own `theme-resources/` tree, since Keycloak searches every registered
`ThemeResourceProvider` rather than requiring them to chain through a single
`parent=` (which only supports one theme at a time).

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

**4. Expose the attribute with a stock protocol mapper, set to multi-valued.** The plugin
writes a `gameIdentityIds` user attribute; map it with `oidc-usermodel-attribute-mapper`
(`gameIdentityIds` → claim `game_identity_ids`) and set **`multivalued: true`**, or the
claim silently carries only the player's first identity. No custom mapper is needed.

Keycloak stores each value of a multi-valued attribute as its own `USER_ATTRIBUTE` row,
which is what lets the uniqueness lookup find a user by *any* of their identities. That is
expected but load-bearing, so `scripts/integration-test.sh` proves it against a live
instance rather than assuming it.

Keycloak 26's declarative User Profile hides unmanaged attributes from Admin API *reads*,
so set `unmanagedAttributePolicy` (or declare `gameIdentityIds` in the profile) if you want
to inspect it there. The plugin's own write bypasses User Profile and works regardless.

**5. Never grant `accounts:bohemia-gameaccount:manage` to a public or user-facing client.**
It permits minting a PIN for an arbitrary Game Identity ID, which is enough to claim
someone else's game identity. Grant it only to confidential, service-account-enabled
clients. The same reasoning applies to the link URL itself: a `link_pin` is a bearer
credential for one game identity until it is consumed or expires.

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

Mints a PIN for a Game Identity ID no Keycloak user holds yet.
Request body: `{ "bohemiaId": "<id>" }`.

> The request field and the URL path still say `bohemia`. This is the trusted wire between
> the backend and this provider, not the public API (where the rename to `gameIdentityId`
> has already happened), and the deployed backend sends this name. It changes when the
> backend changes it, not before.

```
200  { "pin": "<the PIN>", "expiresInSeconds": 1800 }
400  { "error": "bohemiaId required" }
403  { "error": "trusted scope required" }
409  { "error": "already-linked", "keycloakUserId": "<uuid>" }
```

The `409` is the first line of defence against binding one game identity to two accounts:
once a Game Identity ID is held by any user, minting for it is refused. Note this is
per *identity*, not per user — a player who already linked their PC identity still gets a
PIN for their Xbox one.

### `GET …/status`

Resolves whether a Game Identity ID is held, and by whom — so a backend can map a game
identity → Keycloak user without being granted `realm-management: view-users`.

```
200  { "linked": false }
200  { "linked": true, "keycloakUserId": "<uuid>" }
400  { "error": "bohemiaId required" }
403  { "error": "trusted scope required" }
```

## PIN behaviour

A PIN is minted **when an unlinked player joins the gameserver**, and the TTL starts
there. It does not have to span the gap between portal signup and first join, which may be
days — that gap is bridged by the permanent `gameIdentityIds` attribute, and no PIN exists
during it. Every join by a still-unlinked player mints a fresh one; they are independent
single-use keys, so several can be live at once without conflicting, and unconsumed ones
simply expire.

A caller cannot mint a PIN on a player's behalf from the portal side, by design: for an
unlinked player there is no way to know which Game Identity they are. The PIN flows
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
`Dockerfile` layers that onto `quay.io/keycloak/keycloak:26.0` for local testing; it is
not published.

CI runs `mvn -B verify` on every push and pull request. Publishing a GitHub Release builds
the plugin at that tag's version — tag `v0.2.0` yields
`keycloak-bohemia-gameaccount-0.2.0.jar` — and attaches the jar to the release. Renovate in
[ELifeRPG/keycloak](https://github.com/ELifeRPG/keycloak) picks the new release up from there.

## Testing

`mvn verify` runs the unit tests. Two local stacks cover the rest:

**`./scripts/integration-test.sh`** — headless end-to-end regression run. Builds the
provider image, drives the cases below via curl, asserts on each, and tears itself down:

- **success** — asserts the `gameIdentityIds` attribute actually persisted, not merely that
  a page rendered
- **second and third platform append** — the 1:n proof. The same user links three
  identities and must end up holding all three, in order
- **multi-valued lookup** — each of those three values must be findable on its own, so a
  mint for any of them is refused with `409`. This is the one Keycloak behaviour the
  uniqueness guarantee rests on, proven rather than assumed
- **`link_pin` prefills the form** — the PIN on the `kc_action` URL arrives as the
  `client_request_param_link_pin` client note and comes back in the rendered field
- **invalid PIN**, **replayed PIN** (the consume-once guarantee), **conflict setup** and
  **conflict rejected**

> The explicit `docker build` step is required: `docker-compose.integration-test.yml` pins
> a prebuilt `:dev` image rather than a build context, so `up --build` is a no-op for it.
> Without the build, the suite runs against whatever `:dev` image is already present.

**`./scripts/local-dev-up.sh`** — click-through stack for manual testing. Starts Keycloak
with the provider loaded and bootstraps a realm, a browser client, a test user, a
confidential service-account client holding the trusted scope, and the `game_identity_ids`
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
