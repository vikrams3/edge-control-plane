#!/usr/bin/env bash
# Failover drill: steady traffic to sales.edge.local through the stable Envoys, then the local
# sales backend goes to 0 pods and comes back. Envoy health-checks both sites and moves traffic
# to the failover site (priority 1) and back on its own. Prints, every 5 s, how many requests
# each site answered and how many failed.
#
#   ./scripts/failover-demo.sh                  # kind (failover site = namespace edge-dr)
#   CONTEXT=gke-primary ./scripts/failover-demo.sh   # GKE (failover site = the second cluster)
set -euo pipefail
cd "$(dirname "$0")/.."

# gcloud keeps gke-gcloud-auth-plugin next to itself, which is often not on PATH
command -v gcloud >/dev/null && export PATH="$(dirname "$(readlink -f "$(command -v gcloud)")"):$PATH"
CONTEXT=${CONTEXT:-$(kubectl config current-context)}
K="kubectl --context $CONTEXT -n edge"
DOWN_AT=${DOWN_AT:-20}      # seconds into the run
UP_AT=${UP_AT:-60}
DURATION=${DURATION:-100}
RATE=${RATE:-20}            # requests per second

$K delete pod failover-client --ignore-not-found --wait=true >/dev/null
# One client pod in the cluster, so the numbers do not depend on a port-forward.
$K run failover-client --restart=Never --image=python:3.13-alpine --command -- python3 -u -c "
import time, urllib.request, collections
url, rate, dur = 'http://envoy-stable.edge.svc.cluster.local:10000/', $RATE, $DURATION
start = time.time(); bucket = collections.Counter(); last = 0
while (now := time.time() - start) < dur:
    try:
        req = urllib.request.Request(url, headers={'Host': 'sales.edge.local'})
        body = urllib.request.urlopen(req, timeout=2).read().decode()
        bucket['failover' if 'failover' in body else 'local'] += 1
    except Exception as e:
        bucket['error'] += 1
    if int(now) // 5 > last:
        last = int(now) // 5
        print(f'{last*5:4d}s local={bucket[\"local\"]:4d} failover={bucket[\"failover\"]:4d} errors={bucket[\"error\"]:3d}', flush=True)
        bucket.clear()
    time.sleep(max(0, 1/rate - 0.002))
" >/dev/null
$K wait --for=condition=Ready pod/failover-client --timeout=120s >/dev/null
START=$(date +%s)
$K logs -f failover-client &
LOGS=$!

at() { while [ $(( $(date +%s) - START )) -lt "$1" ]; do sleep 1; done; }
at "$DOWN_AT"; echo "[$(( $(date +%s) - START ))s] local sales scaled to 0";  $K scale deploy/sales --replicas=0 >/dev/null
at "$UP_AT";   echo "[$(( $(date +%s) - START ))s] local sales scaled to 2";  $K scale deploy/sales --replicas=2 >/dev/null
wait $LOGS || true
$K delete pod failover-client --wait=false >/dev/null
