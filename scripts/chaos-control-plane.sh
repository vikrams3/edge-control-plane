#!/usr/bin/env bash
# Resilience test: take the control plane away completely while users send traffic, and show
# that the data plane does not care. Envoy keeps serving its last config; only changes stop.
#   ./scripts/chaos-control-plane.sh          (needs kind-up.sh; Prometheus optional)
set -euo pipefail
cd "$(dirname "$0")/.."
LOAD_SECONDS=${LOAD_SECONDS:-120}; DOWN_AFTER=${DOWN_AFTER:-20}; DOWN_FOR=${DOWN_FOR:-60}
OUT=$(mktemp); trap 'rm -f "$OUT"' EXIT
t0=$(date +%s); ts() { printf '[%3ss]' $(( $(date +%s) - t0 )); }

echo "$(ts) load: ${LOAD_SECONDS}s at about 200 req/s to sales.edge.local through the stable Envoys"
kubectl -n edge delete pod hey-chaos --ignore-not-found >/dev/null
kubectl -n edge run hey-chaos --rm -i --quiet --restart=Never --image=edge-hey:dev \
  --image-pull-policy=IfNotPresent -- -z "${LOAD_SECONDS}s" -c 10 -q 20 -host sales.edge.local \
  http://envoy-stable.edge.svc.cluster.local:10000/ >"$OUT" 2>&1 &
LOAD=$!

sleep "$DOWN_AFTER"
echo "$(ts) control plane scaled to 0"
kubectl -n edge scale deploy/control-plane --replicas=0 >/dev/null
kubectl -n edge wait --for=delete pod -l app=control-plane --timeout=60s >/dev/null
echo "$(ts) control plane gone: $(kubectl -n edge get pods -l app=control-plane --no-headers 2>/dev/null | wc -l | tr -d ' ') pods"

code=$(kubectl -n edge run curl-chaos --rm -i --quiet --restart=Never --image=edge-hey:dev \
  --image-pull-policy=IfNotPresent --command -- wget -q -O- -T 3 http://control-plane.edge.svc.cluster.local:8080/v1/routes \
  >/dev/null 2>&1 && echo reachable || echo unreachable)
echo "$(ts) route changes during the outage: control plane API $code"

if kubectl get namespace monitoring >/dev/null 2>&1; then
  for _ in $(seq 1 30); do
    # capture first: with pipefail, "| grep -q" makes wget die of SIGPIPE on a match
    alerts=$(kubectl -n monitoring exec statefulset/alertmanager-kps-alertmanager -c alertmanager -- \
      wget -q -O- http://localhost:9093/api/v2/alerts 2>/dev/null || true)
    if [[ "$alerts" == *EdgeControlPlaneDown* ]]; then
      echo "$(ts) alert EdgeControlPlaneDown is firing"; break
    fi
    [ $(( $(date +%s) - t0 )) -ge $(( DOWN_AFTER + DOWN_FOR )) ] && break
    sleep 5
  done
fi

sleep $(( DOWN_AFTER + DOWN_FOR - ($(date +%s) - t0) > 0 ? DOWN_AFTER + DOWN_FOR - ($(date +%s) - t0) : 0 ))
echo "$(ts) control plane scaled back to 1"
kubectl -n edge scale deploy/control-plane --replicas=1 >/dev/null
kubectl -n edge rollout status deploy/control-plane --timeout=180s >/dev/null
up=$(date +%s)
until kubectl -n edge logs deploy/control-plane 2>/dev/null | grep -q 'Pushed'; do sleep 1; done
for _ in $(seq 1 60); do
  n=$(kubectl -n edge exec deploy/control-plane -- wget -q -O- localhost:8080/actuator/prometheus 2>/dev/null \
      | awk '/^edge_envoys_connected/ {s+=$2} END {print s+0}')
  [ "$n" -ge 3 ] && break; sleep 1
done
echo "$(ts) control plane ready; all $n Envoys reconnected $(( $(date +%s) - up ))s after it became ready"

wait $LOAD || true
total=$(awk '/^  \[[0-9]+\]/ && s==1 {gsub(/[^0-9]/,"",$1); t+=$2} /^Status code distribution/ {s=1} /^Error distribution/ {s=0} END {print t+0}' "$OUT")
ok=$(awk '/^  \[200\]/ {print $2}' "$OUT")
errs=$(awk '/^Error distribution/ {s=1; next} s && /^  \[/ {gsub(/[^0-9]/,"",$1); n+=$1} END {print n+0}' "$OUT")
echo "$(ts) users: ${ok:-0} of $total responses were 200, $(( total - ${ok:-0} )) non-200, $errs transport errors"
grep -E 'Requests/sec| 99% in' "$OUT" | sed 's/^/        /'
