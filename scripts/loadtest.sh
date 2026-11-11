#!/usr/bin/env bash
# Step 5: load tableau through the stable Envoy from inside the cluster, and record
# replicas, requests/s and latency per minute while the HPA scales it out.
#   ./scripts/loadtest.sh            # 4 x 60 s at 50 concurrent connections
#   ROUNDS=3 SECONDS_PER_ROUND=45 CONCURRENCY=30 ./scripts/loadtest.sh
set -euo pipefail
cd "$(dirname "$0")/.."

ROUNDS=${ROUNDS:-4}
SECONDS_PER_ROUND=${SECONDS_PER_ROUND:-60}
CONCURRENCY=${CONCURRENCY:-50}
TARGET=http://envoy-stable.edge.svc.cluster.local:10000/
OUT=loadtest/results-$(date +%Y%m%d-%H%M).md

replicas() { kubectl -n edge get deploy tableau -o jsonpath='{.status.readyReplicas}'; }
cpu() { kubectl -n edge get hpa tableau -o jsonpath='{.status.currentMetrics[0].resource.current.averageUtilization}' 2>/dev/null || true; }

echo "==> Waiting for tableau to settle at 1 replica (the HPA scales down after 5 idle minutes)"
until [ "$(replicas)" = 1 ]; do sleep 10; done

{
  echo "| Minute | Ready replicas at start | HPA CPU at start | Requests/s | p50 (ms) | p99 (ms) | Non-2xx |"
  echo "| --- | --- | --- | --- | --- | --- | --- |"
} > "$OUT"

for i in $(seq 1 "$ROUNDS"); do
  r=$(replicas); c=$(cpu)
  echo "==> Round $i/$ROUNDS: $r ready replicas, HPA CPU ${c:-?}%, ${SECONDS_PER_ROUND}s at -c $CONCURRENCY"
  res=$(kubectl -n edge run "hey-$i" --rm -i --quiet --restart=Never --image=edge-hey:dev \
          --image-pull-policy=IfNotPresent -- \
          -z "${SECONDS_PER_ROUND}s" -c "$CONCURRENCY" -host tableau.edge.local "$TARGET")
  rps=$(awk '/Requests\/sec/ {printf "%.0f", $2}' <<<"$res")
  p50=$(awk '/ 50% in/ {printf "%.0f", $3*1000}' <<<"$res")
  p99=$(awk '/ 99% in/ {printf "%.0f", $3*1000}' <<<"$res")
  # non-2xx responses plus transport errors (timeouts, resets)
  bad=$(awk '/^Status code distribution/ {s=1; next} /^Error distribution/ {s=2; next}
             s==1 && /^  \[/ {code=$1; gsub(/[^0-9]/,"",code); if (code !~ /^2/) n+=$2}
             s==2 && /^  \[/ {cnt=$1; gsub(/[^0-9]/,"",cnt); n+=cnt}
             END {print n+0}' <<<"$res")
  echo "| $i | $r | ${c:-?}% | $rps | $p50 | $p99 | $bad |" | tee -a "$OUT"
done

echo; echo "==> Results written to $OUT"
kubectl -n edge get hpa tableau
