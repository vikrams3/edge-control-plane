#!/usr/bin/env bash
# Installs Prometheus, Alertmanager and Grafana (kube-prometheus-stack), then the edge
# scrape configs, alert rules and dashboard. Run after the edge namespace exists.
set -euo pipefail
cd "$(dirname "$0")/.."

helm repo add prometheus-community https://prometheus-community.github.io/helm-charts >/dev/null 2>&1 || true
helm repo update prometheus-community >/dev/null
echo "==> Installing kube-prometheus-stack (takes a minute or two)"
helm upgrade --install kps prometheus-community/kube-prometheus-stack --version 93.1.2 \
  --namespace monitoring --create-namespace -f k8s/monitoring/values.yaml --wait --timeout 10m

echo "==> Edge monitors, alert rules and Grafana dashboard"
kubectl apply -f k8s/monitoring/edge-monitors.yaml
kubectl -n edge create configmap edge-dashboard --from-file=k8s/monitoring/edge-dashboard.json \
  --dry-run=client -o yaml | kubectl label --local -f - grafana_dashboard=1 -o yaml | kubectl apply -f -
