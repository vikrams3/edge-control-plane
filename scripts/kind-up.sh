#!/usr/bin/env bash
# Creates a local Kubernetes cluster with kind, builds the control plane image,
# deploys everything, and starts port-forwards for the demo.
set -euo pipefail
cd "$(dirname "$0")/.."

CLUSTER=edge

if ! kind get clusters | grep -qx "$CLUSTER"; then
  echo "==> Creating kind cluster '$CLUSTER'"
  kind create cluster --name "$CLUSTER"
fi

echo "==> Building control plane image (compiles and runs unit tests inside Docker)"
docker build -t edge-control-plane:dev ./control-plane

echo "==> Loading image into kind"
kind load docker-image edge-control-plane:dev --name "$CLUSTER"

echo "==> Deploying"
kubectl apply -f k8s/00-namespace.yaml
kubectl apply -f k8s/
kubectl -n edge rollout restart deployment/control-plane   # pick up a rebuilt image
kubectl -n edge rollout status deployment/control-plane --timeout=180s
kubectl -n edge rollout status deployment/envoy-canary --timeout=120s
kubectl -n edge rollout status deployment/envoy-stable --timeout=120s
kubectl -n edge rollout status deployment/tableau --timeout=120s
kubectl -n edge rollout status deployment/sales --timeout=120s

echo "==> Port-forwarding (control plane :8080, stable Envoy :10000, canary Envoy :10001)"
./scripts/port-forward.sh
