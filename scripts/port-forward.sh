#!/usr/bin/env bash
# Forwards cluster services to localhost so scripts/demo.sh works the same as in Docker mode.
set -euo pipefail
pkill -f "kubectl -n edge port-forward" 2>/dev/null || true
kubectl -n edge port-forward svc/control-plane 8080:8080 >/dev/null 2>&1 &
kubectl -n edge port-forward svc/envoy-stable 10000:10000 >/dev/null 2>&1 &
kubectl -n edge port-forward svc/envoy-canary 10001:10000 >/dev/null 2>&1 &
sleep 3
echo "Ready: control plane http://localhost:8080, stable Envoy :10000, canary Envoy :10001"
echo "Stop with: pkill -f 'kubectl -n edge port-forward'"
