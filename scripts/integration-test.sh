#!/usr/bin/env bash
set -euo pipefail

BASE=""
REALM="spi-integration-test"
TRUSTED_SCOPE="accounts:bohemia-gameaccount:manage"
TRUSTED_SECRET="trusted-minter-secret"
COOKIES=$(mktemp)
trap 'rm -f "$COOKIES"' EXIT

wait_for_keycloak() {
  # Try both addresses every iteration rather than committing to one up
  # front, since Keycloak takes a few seconds to start listening at all.
  #
  # localhost works when this script runs directly on the machine hosting
  # Docker. host.docker.internal works when it runs inside this repo's own
  # devcontainer instead: containers started here via docker-outside-of-docker
  # are *siblings* on the host's real Docker daemon, not nested inside the
  # devcontainer, so the devcontainer's own localhost never reaches them
  # (confirmed directly: curl localhost:<port> from inside the devcontainer
  # fails to connect even though the same port is reachable from the bare
  # host) -- host.docker.internal is the portable way to reach the host from
  # in there (wired up in .devcontainer/compose.yml's extra_hosts). This
  # script is fully headless (no human browser involved), so unlike
  # scripts/local-dev-up.sh there's no separate "browser-facing" URL to
  # track -- whichever address works is used throughout.
  local candidates=("http://localhost:18082" "http://host.docker.internal:18082")
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

extract_form_action() {
  # $1 = html file. Prints the first <form ...action="..."> URL, unescaped.
  grep -oE '<form[^>]*action="[^"]*"' "$1" | head -1 \
    | sed -E 's/.*action="([^"]*)"/\1/' | sed 's/&amp;/\&/g'
}

# docker-compose.integration-test.yml pins a prebuilt image rather than a build
# context, so `up --build` is a no-op for it -- without this explicit build the
# stack silently comes up on whatever `:dev` image happens to be lying around,
# and the whole suite then tests a stale jar. Found exactly that way.
echo "=== build provider image ==="
if [ ! -f target/keycloak-bohemia-gameaccount-*.jar ] 2>/dev/null && ! ls target/keycloak-bohemia-gameaccount-*.jar >/dev/null 2>&1; then
  echo "target/keycloak-bohemia-gameaccount-*.jar missing -- run the Maven package step first (see README)" >&2
  exit 1
fi
docker build -q -t eliferpg/keycloak-bohemia-gameaccount:dev .

echo "=== docker compose up ==="
docker compose -f docker-compose.integration-test.yml up -d --force-recreate
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

# A custom required action must first be registered for the realm (this
# creates the RequiredActionProviderModel row) before it can be updated via
# PUT below. Confirmed empirically: PUT alone returns
# {"error":"Failed to find required action"} even though the provider is
# correctly discovered (visible via GET
# .../authentication/unregistered-required-actions) -- this is a standard
# two-step Keycloak Admin REST API requirement, not a defect in this SPI.
curl -s -X POST "$BASE/admin/realms/$REALM/authentication/register-required-action" \
  -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"providerId":"link-bohemia-gameaccount","name":"Link Bohemia Game Account"}'

curl -s -X PUT "$BASE/admin/realms/$REALM/authentication/required-actions/link-bohemia-gameaccount" \
  -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"alias":"link-bohemia-gameaccount","name":"Link Bohemia Game Account","providerId":"link-bohemia-gameaccount","enabled":true,"defaultAction":false}'

# VERIFY_PROFILE can fire on the seeded bohemia_* identity below once it
# becomes the auth session's user mid-flow (Keycloak re-evaluates required
# actions for whoever the CURRENT authenticated user is, and an admin-created
# user reassigned mid-merge trips its "needs review" check even with a full
# profile) -- confirmed empirically while writing scripts/local-dev-up.sh.
# Disabling it realm-wide is the same remedy; a realm-config workaround, not
# an SPI defect.
curl -s -X PUT "$BASE/admin/realms/$REALM/authentication/required-actions/VERIFY_PROFILE" \
  -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"alias":"VERIFY_PROFILE","name":"Verify Profile","providerId":"VERIFY_PROFILE","enabled":false,"defaultAction":false,"priority":90,"config":{}}'

# browser client the human/test player authenticates through
curl -s -X POST "$BASE/admin/realms/$REALM/clients" -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"clientId":"test-client","publicClient":true,"standardFlowEnabled":true,"redirectUris":["http://localhost/*"]}'

# Keycloak 26's declarative User Profile hides attributes outside its declared
# schema from Admin REST API reads. The SPI writes `bohemiaId` through
# UserModel.setSingleAttribute, which bypasses User Profile and works
# regardless -- but the success assertion below reads the attribute back over
# the Admin API to prove the binding persisted, and that read needs ADMIN_EDIT.
# Same pattern as scripts/local-dev-up.sh.
curl -s "$BASE/admin/realms/$REALM/users/profile" -H "$AUTH" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); d['unmanagedAttributePolicy']='ADMIN_EDIT'; print(json.dumps(d))" \
  > /tmp/integration-test-profile.json
curl -s -X PUT "$BASE/admin/realms/$REALM/users/profile" -H "$AUTH" -H "Content-Type: application/json" \
  -d @/tmp/integration-test-profile.json
rm -f /tmp/integration-test-profile.json

# A confidential service-account client -- the trusted caller allowed to mint
# PINs. In production this is the backend, called when an unlinked player joins.
curl -s -X POST "$BASE/admin/realms/$REALM/client-scopes" -H "$AUTH" -H "Content-Type: application/json" \
  -d "{\"name\":\"$TRUSTED_SCOPE\",\"protocol\":\"openid-connect\",\"attributes\":{\"include.in.token.scope\":\"true\"}}"
curl -s -X POST "$BASE/admin/realms/$REALM/clients" -H "$AUTH" -H "Content-Type: application/json" \
  -d "{\"clientId\":\"trusted-minter\",\"publicClient\":false,\"secret\":\"$TRUSTED_SECRET\",\"serviceAccountsEnabled\":true,\"standardFlowEnabled\":false,\"defaultClientScopes\":[\"$TRUSTED_SCOPE\"]}"

mint_pin() {
  # Mints a PIN through the real endpoint as the trusted caller. $1=bohemiaId.
  local bohemia_id="$1"
  local token
  token=$(curl -s -X POST "$BASE/realms/$REALM/protocol/openid-connect/token" \
    -d "grant_type=client_credentials&client_id=trusted-minter&client_secret=$TRUSTED_SECRET" \
    | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])")
  curl -s -X POST "$BASE/realms/$REALM/bohemia-gameaccount/pin" \
    -H "Authorization: Bearer $token" -H "Content-Type: application/json" \
    -d "{\"bohemiaId\":\"$bohemia_id\"}" \
    | python3 -c "import sys,json;print(json.load(sys.stdin)['pin'])"
}

echo "=== mint PINs via the real trusted endpoint ==="
SUCCESS_BOHEMIA_ID="BOHEMIA-SUCCESS-001"
SUCCESS_PIN=$(mint_pin "$SUCCESS_BOHEMIA_ID")
echo "minted PIN for $SUCCESS_BOHEMIA_ID: $SUCCESS_PIN"

# Conflict case: a second bohemiaId that is already bound to another user, so
# binding it again must be refused rather than silently duplicated. Keycloak
# does not enforce this itself -- verified against a live instance -- so this
# case exists specifically to pin our own uniqueness check.
# Both PINs must be minted BEFORE either is redeemed: once a bohemiaId is
# bound, POST /pin refuses to mint for it again (409), which is itself the
# first line of defence against double-binding.
CONFLICT_BOHEMIA_ID="BOHEMIA-CONFLICT-001"
CONFLICT_PIN=$(mint_pin "$CONFLICT_BOHEMIA_ID")
CONFLICT_PIN_2=$(mint_pin "$CONFLICT_BOHEMIA_ID")
echo "minted PINs for $CONFLICT_BOHEMIA_ID: $CONFLICT_PIN / $CONFLICT_PIN_2"

# A PIN that was never minted is simply invalid. Note that an EXPIRED PIN is
# now indistinguishable from an invalid one: PINs live in SingleUseObjects, so
# there is no expiry marker left behind to report on, and collapsing the two
# also removes a PIN-probing oracle. The old "expired PIN" case is gone for
# that reason, not by oversight.
INVALID_PIN="GARBAGE9"

run_case() {
  # $4 (expected_message), if set, is the substring expected in the rendered
  # kc-feedback-text span (the failure cases). If empty, the case is expected
  # to succeed and leave the action page entirely (no /tmp/step3.html at all).
  # $5 (expect_bohemia_id), if set, is asserted to be the bohemiaId attribute
  # actually persisted on the user afterwards -- the real proof the bind stuck.
  local label="$1" username="$2" pin="$3" expected_message="${4:-}" expect_bohemia_id="${5:-}"
  local slug="${label//[^A-Za-z0-9]/_}"
  echo "=== case: $label (pin=$pin) ==="
  rm -f "$COOKIES" /tmp/step1.html /tmp/step2.html /tmp/step3.html

  # test user with no pending required actions other than ours, and profile
  # fields set so Keycloak's default VERIFY_PROFILE required action doesn't
  # also fire (belt-and-suspenders alongside disabling it realm-wide above).
  curl -s -X POST "$BASE/admin/realms/$REALM/users" -H "$AUTH" -H "Content-Type: application/json" \
    -d "{\"username\":\"$username\",\"enabled\":true,\"emailVerified\":true,\"email\":\"$username@example.test\",\"firstName\":\"Test\",\"lastName\":\"User\",\"credentials\":[{\"type\":\"password\",\"value\":\"pass1234\",\"temporary\":false}]}"

  # kc_action is what brings up the PIN form. The action never adds itself, so
  # without this parameter the login just completes -- which the "no linking"
  # case below asserts on directly.
  curl -s -c "$COOKIES" -L \
    "$BASE/realms/$REALM/protocol/openid-connect/auth?client_id=test-client&response_type=code&scope=openid&redirect_uri=http://localhost/callback&kc_action=link-bohemia-gameaccount" \
    -o /tmp/step1.html -w "GET /auth -> %{http_code}\n"

  local login_action
  login_action=$(extract_form_action /tmp/step1.html)
  echo "login form action: $login_action"

  curl -s -c "$COOKIES" -b "$COOKIES" -L "$login_action" \
    --data-urlencode "username=$username" --data-urlencode "password=pass1234" \
    -o /tmp/step2.html -w "POST login -> %{http_code} url=%{url_effective}\n"

  local challenge_action
  challenge_action=$(extract_form_action /tmp/step2.html)
  echo "required-action form action: $challenge_action"

  # On success, Keycloak finishes the OAuth flow and redirects out to the
  # client's redirect_uri (http://localhost/callback), which nothing in this
  # test harness listens on -- curl -L then fails to connect (exit 7). That
  # failure IS the success signal (the flow left our required action and
  # completed normally), so don't let it abort the script.
  curl -s -c "$COOKIES" -b "$COOKIES" -L "$challenge_action" \
    --data-urlencode "pin=$pin" \
    -o /tmp/step3.html -w "POST pin -> %{http_code} url=%{url_effective} time_total=%{time_total}s\n" \
    || echo "(curl could not follow the final redirect out of the app -- expected for the success case)"

  echo "--- result page excerpt ---"
  # Note: Keycloak's msg() resolves message keys (e.g. linkBohemiaGameAccountInvalidPin)
  # to their localized English text from messages_en.properties before rendering
  # -- the raw key never appears in the HTML. So we grep for the resolved
  # kc-feedback-text span content instead of the key name. For the success
  # case there is no /tmp/step3.html at all (the flow redirected out of the
  # app to the OAuth client's redirect_uri), which is itself the pass signal.
  if [ -f /tmp/step3.html ]; then
    cp /tmp/step3.html "/tmp/step3-${slug}.html"
    local feedback
    feedback=$(grep -oE '<span[^>]*class="[^"]*kc-feedback-text[^"]*"[^>]*>[^<]*' /tmp/step3.html \
      | sed -E 's/^.*>//' || true)
    echo "$feedback"
    if [ -z "$expected_message" ]; then
      echo "FAIL: $label expected the flow to complete (no result page), but /tmp/step3.html was produced with message: $feedback"
      exit 1
    fi
    if [[ "$feedback" != *"$expected_message"* ]]; then
      echo "FAIL: $label expected feedback text to contain '$expected_message', got: $feedback"
      exit 1
    fi
  else
    echo "(no step3.html -- flow left the action page, as expected on success)"
    if [ -n "$expected_message" ]; then
      echo "FAIL: $label expected feedback text containing '$expected_message', but the flow completed instead (no step3.html)"
      exit 1
    fi
  fi

  # The real assertion for a successful bind: the attribute is actually on the
  # user afterwards. Rendering alone proves nothing persisted.
  if [ -n "$expect_bohemia_id" ]; then
    local actual
    actual=$(curl -s "$BASE/admin/realms/$REALM/users?username=$username&exact=true" -H "$AUTH" \
      | python3 -c "import sys,json;u=json.load(sys.stdin)[0];print((u.get('attributes') or {}).get('bohemiaId',[''])[0])")
    if [ "$actual" != "$expect_bohemia_id" ]; then
      echo "FAIL: $label expected persisted bohemiaId '$expect_bohemia_id', got '$actual'"
      exit 1
    fi
    echo "persisted bohemiaId=$actual"
  fi
  echo "PASS: $label"
  echo
}

run_case "success" "player-success" "$SUCCESS_PIN" "" "$SUCCESS_BOHEMIA_ID"

# The rendered feedback text is HTML-escaped (apostrophe becomes &#39;),
# confirmed by an actual run against a live container -- match the escaped
# form rather than the raw messages_en.properties text.
run_case "invalid PIN" "player-invalid" "$INVALID_PIN" "isn&#39;t valid"

# A PIN is single-use: replaying the one the success case just consumed must
# fail exactly like an unknown PIN. This is the regression test for the
# consume-once guarantee.
run_case "replayed PIN" "player-replay" "$SUCCESS_PIN" "isn&#39;t valid"

# Bind CONFLICT_BOHEMIA_ID to one user, then try to bind it to another.
run_case "conflict setup" "player-conflict-a" "$CONFLICT_PIN" "" "$CONFLICT_BOHEMIA_ID"
run_case "conflict rejected" "player-conflict-b" "$CONFLICT_PIN_2" "already linked to a different"

echo "=== all cases passed ==="

echo "=== docker compose down ==="
docker compose -f docker-compose.integration-test.yml down -v
