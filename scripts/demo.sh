#!/usr/bin/env bash
# Walks through the demo: route traffic, promote a good change, auto-roll-back a bad one.
# Works in both modes (docker compose, or kind with scripts/port-forward.sh running).
set -euo pipefail
cd "$(dirname "$0")/.."

CP=${CP:-http://localhost:8080}
EDGE=${EDGE:-http://localhost:10000}

step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
pause() { [ "${NO_PAUSE:-}" = 1 ] || read -r -p "    (press Enter) " _; }

step "Current routes on each Envoy group"
curl -s "$CP/v1/routes"; echo
pause

step "Users hit the stable Envoy; it routes by hostname"
echo -n "tableau.edge.local -> "; curl -s -H 'Host: tableau.edge.local' "$EDGE/"; echo
echo -n "sales.edge.local   -> "; curl -s -H 'Host: sales.edge.local' "$EDGE/"
pause

step "Good change: add crm.edge.local -> sales. Expect: promoted"
curl -s -X POST "$CP/v1/rollouts?wait=true" -H 'Content-Type: application/json' -d '{
  "tableau.edge.local": "tableau",
  "sales.edge.local": "sales",
  "crm.edge.local": "sales"}'; echo
echo -n "crm.edge.local on stable -> "; curl -s -H 'Host: crm.edge.local' "$EDGE/"
pause

step "Typo in the backend name (salez). Expect: 400, rejected before any Envoy sees it"
curl -s -w '  (HTTP %{http_code})' -X POST "$CP/v1/rollouts?wait=true" -H 'Content-Type: application/json' -d '{
  "tableau.edge.local": "tableau",
  "sales.edge.local": "salez",
  "crm.edge.local": "sales"}'; echo
pause

step "Bad change: move crm.edge.local to billing, which is down. Expect: rolled_back"
curl -s -X POST "$CP/v1/rollouts?wait=true" -H 'Content-Type: application/json' -d '{
  "tableau.edge.local": "tableau",
  "sales.edge.local": "sales",
  "crm.edge.local": "billing"}'; echo
echo -n "crm.edge.local on stable is still fine -> "; curl -s -H 'Host: crm.edge.local' "$EDGE/"
pause

if [ -f certs/tls.crt ]; then
  step "Same routes over HTTPS: Envoy terminates TLS with a certificate sent over SDS"
  echo -n "https://sales.edge.local:10443 -> "
  curl -s --cacert certs/tls.crt --resolve sales.edge.local:10443:127.0.0.1 https://sales.edge.local:10443/
  pause
fi

step "Rollout history (last 3; every rollout is a row in PostgreSQL)"
curl -s "$CP/v1/rollouts" | python3 -c '
import json, sys
for r in json.load(sys.stdin)[-3:]:
    print("v{version:<5} {outcome:<12} canary {canaryAck:<6} probe failures {probeFailures}/{probeRequests}  {routes}".format(**r))'

