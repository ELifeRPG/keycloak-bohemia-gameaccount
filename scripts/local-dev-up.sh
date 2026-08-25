#!/usr/bin/env bash
# Brings up a local Keycloak stack (via compose.yml) for manually clicking
# through the link-bohemia-gameaccount flow in a real browser. There's no external
# Central API or other outbound HTTP call involved -- linking happens
# entirely inside this SPI: a trusted caller mints a PIN, and the player
# types it into Keycloak's own form, which binds the bohemiaId onto the
# already-authenticated user.
#
# This is the interactive counterpart to scripts/integration-test.sh (which
# drives the same flow headlessly via curl for automated regression
# checking, then tears everything down). This script leaves the stack
# running afterward so you can actually open a browser and try it.
set -euo pipefail

REALM="local-dev"
TRUSTED_SCOPE="accounts:bohemia-gameaccount:manage"
TRUSTED_SECRET="trusted-minter-secret"
# The URL a human types into their own browser -- always localhost, whether
# this script runs on the bare host or inside the devcontainer. On the bare
# host that's trivially correct; inside the devcontainer it's correct too,
# because that's what VS Code's own port forwarding maps to on the human's
# actual machine (see README.md's "Note on ports").
BROWSER_BASE="http://localhost:18080"
# The address THIS SCRIPT uses for its own curl calls -- auto-detected
# below, since it differs depending on where the script itself runs.
BASE=""

wait_for_keycloak() {
  # Try both addresses every iteration rather than committing to one up
  # front, since Keycloak takes a few seconds to start listening at all --
  # on the first tries neither would succeed regardless of which is
  # actually reachable.
  #
  # localhost works when this script runs directly on the machine hosting
  # Docker. host.docker.internal works when it runs inside this repo's own
  # devcontainer instead: containers started here via docker-outside-of-docker
  # are *siblings* on the host's real Docker daemon, not nested inside the
  # devcontainer, so the devcontainer's own localhost never reaches them
  # (confirmed directly: curl localhost:<port> from inside the devcontainer
  # fails to connect even though the same port is reachable from the bare
  # host) -- host.docker.internal is the portable way to reach the host from
  # in there (wired up in .devcontainer/compose.yml's extra_hosts).
  local candidates=("http://localhost:18080" "http://host.docker.internal:18080")
  for i in $(seq 1 60); do
    for candidate in "${candidates[@]}"; do
      if curl -sf --max-time 2 "$candidate/realms/master" >/dev/null 2>&1; then
        BASE="$candidate"
        echo "Keycloak ready after ${i}s (reachable via $BASE)"
        return 0
      fi
    done
    sleep 1
  done
  echo "Keycloak did not become ready in time" >&2
  echo "(tried: ${candidates[*]})" >&2
  exit 1
}

admin_token() {
  curl -s -X POST "$BASE/realms/master/protocol/openid-connect/token" \
    -d "grant_type=password&client_id=admin-cli&username=admin&password=admin" \
    | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])"
}

echo "=== docker compose up ==="
docker compose up -d --build
wait_for_keycloak

echo "=== bootstrap realm/client/user via Admin API ==="
TOKEN=$(admin_token)
AUTH="Authorization: Bearer $TOKEN"

# No loginTheme override needed: link-bohemia-gameaccount.ftl ships under
# theme-resources/templates in this jar, which Keycloak's ClasspathThemeResource-
# ProviderFactory makes available to whatever theme is active -- including the
# realm's default here. Production separately deploys keycloak-theme-eliferpg
# and sets loginTheme=eliferpg for the styled look; this page renders under either.
curl -s -X POST "$BASE/admin/realms" -H "$AUTH" -H "Content-Type: application/json" \
  -d "{\"realm\":\"$REALM\",\"enabled\":true}"

# Register before enabling -- PUT alone 404s until this runs once per realm.
curl -s -X POST "$BASE/admin/realms/$REALM/authentication/register-required-action" \
  -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"providerId":"link-bohemia-gameaccount","name":"Link Bohemia Game Account"}'

curl -s -X PUT "$BASE/admin/realms/$REALM/authentication/required-actions/link-bohemia-gameaccount" \
  -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"alias":"link-bohemia-gameaccount","name":"Link Bohemia Game Account","providerId":"link-bohemia-gameaccount","enabled":true,"defaultAction":false}'

# VERIFY_PROFILE fires on admin-created users and would interleave with the
# action we're actually trying to demo. Nothing to do with this SPI -- it's
# just noise in a click-through demo, so switch it off realm-wide.
curl -s -X PUT "$BASE/admin/realms/$REALM/authentication/required-actions/VERIFY_PROFILE" \
  -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"alias":"VERIFY_PROFILE","name":"Verify Profile","providerId":"VERIFY_PROFILE","enabled":false,"defaultAction":false,"priority":90,"config":{}}'

# browser client you'll actually log in through
curl -s -X POST "$BASE/admin/realms/$REALM/clients" -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"clientId":"browser-client","publicClient":true,"standardFlowEnabled":true,"redirectUris":["http://localhost:18080/*"]}'

# Keycloak 26's declarative User Profile hides attributes that aren't in its
# declared schema from Admin REST API reads. The SPI writes `bohemiaId` via
# UserModel.setSingleAttribute, which bypasses User Profile entirely and works
# regardless -- but without ADMIN_EDIT you cannot *see* the result afterwards
# to confirm the link worked, which makes this script useless as a demo.
curl -s "$BASE/admin/realms/$REALM/users/profile" -H "$AUTH" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); d['unmanagedAttributePolicy']='ADMIN_EDIT'; print(json.dumps(d))" \
  > /tmp/local-dev-up-profile.json
curl -s -X PUT "$BASE/admin/realms/$REALM/users/profile" -H "$AUTH" -H "Content-Type: application/json" \
  -d @/tmp/local-dev-up-profile.json
rm -f /tmp/local-dev-up-profile.json

# A confidential service-account client standing in for the trusted caller
# (in production: the backend, when an unlinked player joins the gameserver).
# It's the only kind of client allowed to mint a PIN for an arbitrary
# bohemiaId, so it must never be a public/browser client.
curl -s -X POST "$BASE/admin/realms/$REALM/client-scopes" -H "$AUTH" -H "Content-Type: application/json" \
  -d "{\"name\":\"$TRUSTED_SCOPE\",\"protocol\":\"openid-connect\",\"attributes\":{\"include.in.token.scope\":\"true\"}}"
curl -s -X POST "$BASE/admin/realms/$REALM/clients" -H "$AUTH" -H "Content-Type: application/json" \
  -d "{\"clientId\":\"trusted-minter\",\"publicClient\":false,\"secret\":\"$TRUSTED_SECRET\",\"serviceAccountsEnabled\":true,\"standardFlowEnabled\":false,\"defaultClientScopes\":[\"$TRUSTED_SCOPE\"]}"

# Expose bohemiaId as a `bohemia_id` token claim, exactly as the real realm
# must (this is a stock User Attribute mapper -- no custom mapper SPI).
BROWSER_CLIENT_ID=$(curl -s "$BASE/admin/realms/$REALM/clients?clientId=browser-client" -H "$AUTH" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)[0]['id'])")
curl -s -X POST "$BASE/admin/realms/$REALM/clients/$BROWSER_CLIENT_ID/protocol-mappers/models" \
  -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"name":"bohemia-id","protocol":"openid-connect","protocolMapper":"oidc-usermodel-attribute-mapper",
       "config":{"user.attribute":"bohemiaId","claim.name":"bohemia_id","jsonType.label":"String",
                 "access.token.claim":"true","id.token.claim":"true","userinfo.token.claim":"true"}}'

# one test user, profile fields set so Keycloak's default VERIFY_PROFILE
# required action doesn't also fire and get in the way
curl -s -X POST "$BASE/admin/realms/$REALM/users" -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"username":"player1","enabled":true,"emailVerified":true,"email":"player1@example.test","firstName":"Test","lastName":"Player","credentials":[{"type":"password","value":"pass1234","temporary":false}]}'

# Mint a demo PIN through the REAL endpoint, as the trusted caller -- this is
# the same call the backend makes when an unlinked player joins, so the demo
# exercises the endpoint rather than faking its effect via the Admin API.
MINT_TOKEN=$(curl -s -X POST "$BASE/realms/$REALM/protocol/openid-connect/token" \
  -d "grant_type=client_credentials&client_id=trusted-minter&client_secret=$TRUSTED_SECRET" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])")
DEMO_PIN=$(curl -s -X POST "$BASE/realms/$REALM/bohemia-gameaccount/pin" \
  -H "Authorization: Bearer $MINT_TOKEN" -H "Content-Type: application/json" \
  -d '{"bohemiaId":"DEMO-BOHEMIA-001"}' \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('pin','<mint failed: '+str(d)+'>'))")

echo
echo "=== ready ==="
if [ "$BASE" != "$BROWSER_BASE" ]; then
  echo "(bootstrapped via $BASE -- this script is running somewhere that can't"
  echo " reach localhost:18080 directly, e.g. inside this repo's own"
  echo " devcontainer. The URLs below are still localhost, though: open them"
  echo " on whichever machine your actual browser runs on -- if that's not"
  echo " this one, see README.md's \"Note on ports\" for VS Code port forwarding.)"
  echo
fi
AUTH_URL="$BROWSER_BASE/realms/$REALM/protocol/openid-connect/auth?client_id=browser-client&response_type=code&scope=openid&redirect_uri=$BROWSER_BASE/callback"
echo "Plain login (no linking -- proves the action never interrupts on its own):"
echo "  $AUTH_URL"
echo "Username: player1   Password: pass1234"
echo
# Linking is application-initiated: the portal sends the player here on demand.
# It is deliberately NOT a forced interrupt, so a player who has not joined the
# gameserver yet is never stranded at a PIN prompt they cannot satisfy.
echo "Link a Bohemia game account (this is what the portal's button does):"
echo "  $AUTH_URL&kc_action=link-bohemia-gameaccount"
echo "Enter PIN: $DEMO_PIN   (minted for bohemiaId DEMO-BOHEMIA-001, valid ~30 min)"
echo
echo "Then confirm the binding stuck:"
echo "  the player1 user should have attribute bohemiaId=DEMO-BOHEMIA-001 in the admin console"
echo
echo "Admin console: $BROWSER_BASE/admin (admin/admin)"
echo
echo "When done: docker compose down -v"
