#!/usr/bin/env bash
# Walks through the demo: route traffic, promote a good change, auto-roll-back a bad one.
# Works in both modes (docker compose, or kind with scripts/port-forward.sh running).
set -euo pipefail

CP=${CP:-http://localhost:8080}
EDGE=${EDGE:-http://localhost:10000}

step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
pause() { [ "${NO_PAUSE:-}" = 1 ] || read -r -p "    (press Enter) " _; }

step "Current routes on each Envoy group"
curl -s "$CP/v1/routes"; echo
pause

step "Users hit the stable Envoy; it routes by hostname"
echo -n "tableau.edge.local -> "; curl -s -H 'Host: tableau.edge.local' "$EDGE/"
echo -n "sales.edge.local   -> "; curl -s -H 'Host: sales.edge.local' "$EDGE/"
pause

step "Good change: add crm.edge.local -> sales. Expect: promoted"
curl -s -X POST "$CP/v1/rollouts" -H 'Content-Type: application/json' -d '{
  "tableau.edge.local": "tableau",
  "sales.edge.local": "sales",
  "crm.edge.local": "sales"}'; echo
echo -n "crm.edge.local on stable -> "; curl -s -H 'Host: crm.edge.local' "$EDGE/"
pause

step "Bad change: typo in the backend name (salez). Expect: rolled_back"
curl -s -X POST "$CP/v1/rollouts" -H 'Content-Type: application/json' -d '{
  "tableau.edge.local": "tableau",
  "sales.edge.local": "salez",
  "crm.edge.local": "sales"}'; echo
echo -n "sales.edge.local on stable is still fine -> "; curl -s -H 'Host: sales.edge.local' "$EDGE/"
pause

step "Rollout history"
curl -s "$CP/v1/rollouts"; echo
