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

echo "==> Building the in-cluster load generator (hey)"
docker build -q -t edge-hey:dev ./loadtest

echo "==> Loading images into kind"
kind load docker-image edge-control-plane:dev edge-hey:dev --name "$CLUSTER"

echo "==> Installing metrics-server (the tableau HPA scales on CPU)"
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/download/v0.7.2/components.yaml
# kind's kubelets use self-signed certs; patch once so re-runs don't stack the flag
if ! kubectl -n kube-system get deployment metrics-server -o jsonpath='{.spec.template.spec.containers[0].args}' \
    | grep -q kubelet-insecure-tls; then
  kubectl -n kube-system patch deployment metrics-server --type=json \
    -p '[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'
fi

echo "==> Deploying"
kubectl apply -f k8s/00-namespace.yaml
[ -f certs/tls.crt ] || ./scripts/gen-certs.sh
kubectl -n edge create secret tls edge-tls --cert=certs/tls.crt --key=certs/tls.key \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -f k8s/
kubectl -n edge rollout status statefulset/postgres --timeout=180s
kubectl -n edge rollout restart deployment/control-plane   # pick up a rebuilt image
kubectl -n edge rollout status deployment/control-plane --timeout=180s
kubectl -n edge rollout status deployment/envoy-canary --timeout=120s
kubectl -n edge rollout status deployment/envoy-stable --timeout=120s
kubectl -n edge rollout status deployment/tableau --timeout=120s
kubectl -n edge rollout status deployment/sales --timeout=120s

if [ "${MONITORING:-1}" = 1 ]; then
  ./scripts/monitoring-up.sh      # skip with MONITORING=0
fi

if [ "${KAFKA:-1}" = 1 ]; then
  ./scripts/kafka-up.sh           # skip with KAFKA=0
fi

echo "==> Port-forwarding (control plane :8080, stable Envoy :10000, canary Envoy :10001)"
./scripts/port-forward.sh
