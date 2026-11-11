#!/usr/bin/env bash
# Self-optimizing routing: route tableau.edge.local to tableau-pool (tableau-a + tableau-b),
# slow tableau-b down, and watch the control plane move traffic to tableau-a by live p99;
# then heal tableau-b and watch the split drift back toward even.
#   ./scripts/optimizer-demo.sh               tableau-b gets 300 ms slower: weights shift in 10% steps
#   BREAK=fail ./scripts/optimizer-demo.sh    tableau-b returns 503s: ejected at once, re-tested after
#                                             60 s; the control plane restarts mid-way (split survives)
set -euo pipefail
cd "$(dirname "$0")/.."
SLOW_MINUTES=${SLOW_MINUTES:-5}; HEAL_MINUTES=${HEAL_MINUTES:-3}; DELAY_MS=${DELAY_MS:-300}
BREAK=${BREAK:-slow}
if [ "$BREAK" = fail ]; then BAD="FAIL_PERCENT=100"; GOOD="FAIL_PERCENT=0"; WHAT="returns 503 for every request"
else BAD="DELAY_MS=$DELAY_MS"; GOOD="DELAY_MS=0"; WHAT="adds ${DELAY_MS} ms to every request"; fi
CP=${CP:-http://localhost:8080}
t0=$(date +%s); ts() { printf '[%3ss]' $(( $(date +%s) - t0 )); }
routes() { curl -s "$CP/v1/routes" | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["stable"]))'; }
with() { python3 -c "import json,sys; r=json.loads(sys.argv[1]); r['tableau.edge.local']=sys.argv[2]; print(json.dumps(r))" "$(routes)" "$1"; }
show() {
  pools=$(curl -s "$CP/v1/pools")
  stats=$(kubectl -n edge exec deploy/control-plane -- wget -q -O- \
      "http://analytics.edge.svc.cluster.local:8080/v1/stats?group=stable&by=host&since=$(( ($(date +%s) - 30) * 1000 ))")
  line=$(python3 - "$pools" "$stats" <<'PY'
import json, sys
p = json.loads(sys.argv[1])["tableau-pool"]
t = json.loads(sys.argv[2])["byHost"].get("tableau.edge.local", {})
print("weights a=%3d%%  b=%3d%%   tableau p50 %s ms, p99 %s ms, 5xx %s of %s in 30 s" % (
    p["tableau-a"], p["tableau-b"], t.get("p50Ms", "-"), t.get("p99Ms", "-"),
    t.get("errors5xx", 0), t.get("requests", 0)))
PY
)
  echo "$(ts) $1  $line"
}

echo "$(ts) route tableau.edge.local -> tableau-pool (through the canary)"
curl -s -X POST "$CP/v1/rollouts?wait=true" -H 'Content-Type: application/json' -d "$(with tableau-pool)" \
  | python3 -c 'import json,sys; r=json.load(sys.stdin); print("       ", r["outcome"], "v"+str(r["version"]))'

TOTAL=$(( (SLOW_MINUTES + HEAL_MINUTES) * 60 + 90 ))
kubectl -n edge delete pod hey-opt --ignore-not-found >/dev/null
kubectl -n edge run hey-opt --restart=Never --image=edge-hey:dev --image-pull-policy=IfNotPresent -- \
  -z "${TOTAL}s" -c 4 -q 5 -host tableau.edge.local http://envoy-stable.edge.svc.cluster.local:10000/ >/dev/null
echo "$(ts) load: about 20 req/s to tableau.edge.local on stable, plus the background users"
sleep 60; show "even, both healthy  "

kubectl -n edge set env deploy/tableau-b "$BAD" >/dev/null
kubectl -n edge rollout status deploy/tableau-b --timeout=120s >/dev/null
echo "$(ts) tableau-b now $WHAT"
for i in $(seq 1 $(( SLOW_MINUTES * 2 ))); do
  sleep 30; show "tableau-b bad       "
  if [ "$BREAK" = fail ] && [ "$i" = 3 ]; then
    before=$(curl -s "$CP/v1/pools")
    kubectl -n edge rollout restart deploy/control-plane >/dev/null
    kubectl -n edge rollout status deploy/control-plane --timeout=180s >/dev/null
    ./scripts/port-forward.sh >/dev/null
    echo "$(ts) control plane restarted: split before $before, after $(curl -s "$CP/v1/pools")"
  fi
done

kubectl -n edge set env deploy/tableau-b "$GOOD" >/dev/null
kubectl -n edge rollout status deploy/tableau-b --timeout=120s >/dev/null
echo "$(ts) tableau-b healed"
for _ in $(seq 1 $(( HEAL_MINUTES * 2 ))); do sleep 30; show "tableau-b healed    "; done

kubectl -n edge delete pod hey-opt --ignore-not-found >/dev/null
echo "$(ts) optimizer decisions:"
kubectl -n edge logs deploy/control-plane | grep 'Optimizer: tableau-pool' | grep -v 'not enough' | cut -c95- | tail -25
echo "$(ts) route tableau.edge.local back to the tableau backend"
curl -s -X POST "$CP/v1/rollouts?wait=true" -H 'Content-Type: application/json' -d "$(with tableau)" \
  | python3 -c 'import json,sys; r=json.load(sys.stdin); print("       ", r["outcome"], "v"+str(r["version"]))'
