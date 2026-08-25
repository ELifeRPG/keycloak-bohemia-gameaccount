#!/usr/bin/env sh
# Makes this repo's local testing stacks' ports (see compose.yml,
# docker-compose.integration-test.yml) genuinely listen on *this*
# devcontainer's own localhost, by relaying to host.docker.internal.
#
# Why this exists: containers started from inside the devcontainer via
# docker/docker compose are sibling containers on the host's real Docker
# daemon (docker-outside-of-docker), not nested inside the devcontainer --
# its own localhost never reaches them directly (confirmed: curl
# localhost:<port> from in here fails to connect even though the port is
# reachable from the bare host, via host.docker.internal). That has two
# consequences, both fixed by relaying through a real local listener:
#   1. A plain shell in here (curl, scripts/*.sh) can now just use
#      localhost, no host.docker.internal fallback needed.
#   2. VS Code's automatic port detection -- which only watches ports
#      actually listening inside the devcontainer's own network namespace
#      -- can now see and forward them, the same as any other port a
#      process in here listens on. (Confirmed separately: VS Code's
#      *manual* "Forward a Port" does NOT reliably reach a port that's only
#      bound on a sibling container in at least one real Remote-SSH + Dev
#      Containers setup -- this sidesteps that by making the port
#      genuinely local instead of asking VS Code to reach across.)
#
# Idempotent: safe to run again (e.g. every time VS Code (re)attaches via
# postStartCommand) -- skips ports that already have a live relay running.
set -e

relay_port() {
  port="$1"
  pidfile="/tmp/socat-relay-$port.pid"

  if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile" 2>/dev/null)" 2>/dev/null; then
    return 0
  fi

  nohup socat "TCP-LISTEN:${port},fork,reuseaddr" "TCP:host.docker.internal:${port}" \
    >"/tmp/socat-relay-${port}.log" 2>&1 &
  echo "$!" >"$pidfile"
  echo "Relaying localhost:${port} -> host.docker.internal:${port} (pid $(cat "$pidfile"))"
}

# 18080: compose.yml (./scripts/local-dev-up.sh)
# 18082: docker-compose.integration-test.yml (./scripts/integration-test.sh)
relay_port 18080
relay_port 18082
