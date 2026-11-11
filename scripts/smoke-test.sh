#!/usr/bin/env bash
# Asserts the deployed system works end to end on kind: health, promote, 400 on a typo,
# rollback of a bad change, stable unaffected, HTTPS. Exits non-zero on the first failure.
# Uses its own port-forwards on unusual ports, so it also runs inside the Jenkins container.
set -euo pipefail
CP_PORT=${CP_PORT:-18080}; EDGE_PORT=${EDGE_PORT:-20000}; TLS_PORT=${TLS_PORT:-20443}
CP=http://localhost:$CP_PORT; EDGE=http://localhost:$EDGE_PORT

kubectl -n edge port-forward svc/control-plane "$CP_PORT:8080" >/dev/null 2>&1 & PF1=$!
kubectl -n edge port-forward svc/envoy-stable "$EDGE_PORT:10000" "$TLS_PORT:10443" >/dev/null 2>&1 & PF2=$!
trap 'kill $PF1 $PF2 2>/dev/null || true' EXIT

ok()   { printf '  ok    %s\n' "$*"; }
fail() { printf '  FAIL  %s\n' "$*"; exit 1; }
field() { python3 -c "import json,sys; print(json.load(sys.stdin).get('$1'))"; }

for _ in $(seq 1 30); do curl -sf "$CP/actuator/health" >/dev/null && break; sleep 2; done
[ "$(curl -s "$CP/actuator/health" | field status)" = UP ] && ok "control plane healthy" || fail "control plane not healthy"

GOOD='{"tableau.edge.local":"tableau","sales.edge.local":"sales","crm.edge.local":"sales"}'
out=$(curl -s -X POST "$CP/v1/rollouts?wait=true" -H 'Content-Type: application/json' -d "$GOOD")
[ "$(field outcome <<<"$out")" = promoted ] && ok "good change promoted" || fail "good change: $out"

code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$CP/v1/rollouts" -H 'Content-Type: application/json' \
  -d '{"sales.edge.local":"salez"}')
[ "$code" = 400 ] && ok "unknown backend rejected with 400" || fail "typo returned $code"

BAD='{"tableau.edge.local":"tableau","sales.edge.local":"sales","crm.edge.local":"billing"}'
out=$(curl -s -X POST "$CP/v1/rollouts?wait=true" -H 'Content-Type: application/json' -d "$BAD")
[ "$(field outcome <<<"$out")" = rolled_back ] && ok "bad change rolled back ($(field probeFailures <<<"$out") probe failures)" \
  || fail "bad change: $out"

body=$(curl -s -H 'Host: crm.edge.local' "$EDGE/")
[ "$body" = "hello from sales" ] && ok "stable still serves crm.edge.local" || fail "stable returned: $body"

if kubectl -n edge get secret edge-tls >/dev/null 2>&1; then
  body=$(curl -sk --resolve "sales.edge.local:$TLS_PORT:127.0.0.1" "https://sales.edge.local:$TLS_PORT/")
  [ "$body" = "hello from sales" ] && ok "HTTPS on :10443" || fail "HTTPS returned: $body"
fi
if [ "${SIMULATE_FAILURE:-0}" = 1 ]; then
  fail "simulated failure (SIMULATE_FAILURE=1), to exercise the pipeline's rollback"
fi
echo "Smoke test passed"
