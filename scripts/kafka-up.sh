#!/usr/bin/env bash
# Real-time analytics: Strimzi Kafka (one KRaft node), Fluent Bit shipping Envoy access logs
# to the "envoy-access-logs" topic, and the Kafka Streams analytics service.
set -euo pipefail
cd "$(dirname "$0")/.."

helm repo add strimzi https://strimzi.io/charts/ >/dev/null 2>&1 || true
helm repo add fluent https://fluent.github.io/helm-charts >/dev/null 2>&1 || true
helm repo update strimzi fluent >/dev/null

echo "==> Strimzi operator and a single-node Kafka"
helm upgrade --install strimzi strimzi/strimzi-kafka-operator --version 1.3.0 \
  -n kafka --create-namespace --set watchNamespaces='{kafka}' \
  --set resources.requests.memory=256Mi --wait --timeout 5m >/dev/null
kubectl apply -f k8s/kafka/kafka.yaml
kubectl -n kafka wait kafka/edge-kafka --for=condition=Ready --timeout=600s

echo "==> Fluent Bit: Envoy access logs -> Kafka"
helm upgrade --install fluent-bit fluent/fluent-bit --version 0.58.2 -n kafka \
  -f k8s/kafka/fluent-bit-values.yaml --wait --timeout 5m >/dev/null

echo "==> Analytics service (Kafka Streams)"
docker build -q -t edge-analytics:dev ./analytics >/dev/null
kind load docker-image edge-analytics:dev --name edge
kubectl apply -f k8s/kafka/analytics.yaml
kubectl -n edge rollout restart deployment/analytics
kubectl -n edge rollout status deployment/analytics --timeout=180s
