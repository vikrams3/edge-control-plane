#!/usr/bin/env bash
# Forwards cluster services to localhost so scripts/demo.sh works the same as in Docker mode.
set -euo pipefail
pkill -f "kubectl -n (edge|monitoring) port-forward" 2>/dev/null || true
kubectl -n edge port-forward svc/control-plane 8080:8080 >/dev/null 2>&1 &
kubectl -n edge port-forward svc/envoy-stable 10000:10000 >/dev/null 2>&1 &
kubectl -n edge port-forward svc/envoy-canary 10001:10000 >/dev/null 2>&1 &
if kubectl get namespace monitoring >/dev/null 2>&1; then
  kubectl -n monitoring port-forward svc/kps-grafana 3000:80 >/dev/null 2>&1 &
  kubectl -n monitoring port-forward svc/kps-prometheus 9090:9090 >/dev/null 2>&1 &
  kubectl -n monitoring port-forward svc/kps-alertmanager 9093:9093 >/dev/null 2>&1 &
fi
sleep 3
echo "Ready: control plane http://localhost:8080, stable Envoy :10000, canary Envoy :10001"
kubectl get namespace monitoring >/dev/null 2>&1 && \
  echo "       Grafana http://localhost:3000 (admin/admin), Prometheus :9090, Alertmanager :9093"
echo "Stop with: pkill -f 'kubectl -n (edge|monitoring) port-forward'"
