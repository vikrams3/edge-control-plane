#!/usr/bin/env bash
# Runs the edge on GKE with a failover site in a second region, sized to cost about $0.05/hour:
#   - primary: one zonal GKE cluster (its fee is covered by the GKE free tier) with one spot node,
#     running the same manifests as kind (monitoring and Kafka off)
#   - failover site: one spot e2-small VM in another region running the sales backend, reached
#     over the VPC's internal IP; no public IP, no load balancer
# Envoy in the primary fails sales over to the VM by locality priority (see EdgeConfig.cluster).
# Re-runnable: it skips what already exists. Delete everything with ./scripts/gke-down.sh.
set -euo pipefail
cd "$(dirname "$0")/.."

PROJECT=${PROJECT:-$(gcloud config get-value project 2>/dev/null)}
REGION=${REGION:-us-central1}
ZONE=${ZONE:-us-central1-a}
DR_REGION=${DR_REGION:-us-east1}
DR_ZONE=${DR_ZONE:-us-east1-b}
CLUSTER=edge-primary
DR_VM=edge-dr-sales
REPO=$REGION-docker.pkg.dev/$PROJECT/edge
TAG=${TAG:-$(git rev-parse --short HEAD)$(git diff --quiet HEAD -- control-plane loadtest streaming || echo -dirty)}
CONTEXT=gke-primary
export PATH="$(dirname "$(readlink -f "$(command -v gcloud)")"):$PATH"   # gke-gcloud-auth-plugin
G="gcloud --project $PROJECT --quiet"
K="kubectl --context $CONTEXT"

echo "==> Project $PROJECT, images $REPO:$TAG"
$G services enable container.googleapis.com artifactregistry.googleapis.com compute.googleapis.com

echo "==> Artifact Registry repository"
$G artifacts repositories describe edge --location "$REGION" >/dev/null 2>&1 \
  || $G artifacts repositories create edge --location "$REGION" --repository-format docker
gcloud auth configure-docker "$REGION-docker.pkg.dev" --quiet >/dev/null 2>&1
# GKE nodes pull with the default compute service account
NUMBER=$($G projects describe "$PROJECT" --format='value(projectNumber)')
$G artifacts repositories add-iam-policy-binding edge --location "$REGION" \
  --member "serviceAccount:$NUMBER-compute@developer.gserviceaccount.com" \
  --role roles/artifactregistry.reader >/dev/null

echo "==> Building amd64 images (Maven runs natively; unit tests run in the build)"
# --load then docker push: "buildx build --push" stalled at "pushing layers" with Docker Desktop
for img in control-plane:./control-plane hey:./loadtest streaming:./streaming; do
  docker buildx build -q --platform linux/amd64 -t "$REPO/${img%%:*}:$TAG" --load "${img#*:}" >/dev/null
  docker push -q "$REPO/${img%%:*}:$TAG"
done

echo "==> GKE cluster $CLUSTER (zonal, one spot node)"
if ! $G container clusters describe "$CLUSTER" --zone "$ZONE" >/dev/null 2>&1; then
  $G container clusters create "$CLUSTER" --zone "$ZONE" \
    --num-nodes 1 --machine-type e2-standard-4 --spot \
    --disk-type pd-standard --disk-size 30 \
    --release-channel regular \
    --logging SYSTEM --monitoring SYSTEM --no-enable-managed-prometheus
fi
$G container clusters get-credentials "$CLUSTER" --zone "$ZONE" >/dev/null 2>&1
kubectl config delete-context "$CONTEXT" >/dev/null 2>&1 || true
kubectl config rename-context "gke_${PROJECT}_${ZONE}_${CLUSTER}" "$CONTEXT" >/dev/null

echo "==> Failover site: VM $DR_VM in $DR_ZONE"
# Private Google Access lets a VM with no public IP pull from mirror.gcr.io (Docker Hub mirror)
$G compute networks subnets update default --region "$DR_REGION" --enable-private-ip-google-access
if ! $G compute instances describe "$DR_VM" --zone "$DR_ZONE" >/dev/null 2>&1; then
  $G compute instances create "$DR_VM" --zone "$DR_ZONE" \
    --machine-type e2-small --provisioning-model SPOT --instance-termination-action STOP \
    --image-family cos-stable --image-project cos-cloud \
    --boot-disk-size 10GB --boot-disk-type pd-standard \
    --no-address --tags edge-dr \
    --metadata startup-script='#! /bin/bash
docker rm -f sales 2>/dev/null
docker run -d --name sales --restart=always -p 8080:8080 mirror.gcr.io/hashicorp/http-echo \
  "-text=hello from sales (failover site)" -listen=:8080'
fi
DR_IP=$($G compute instances describe "$DR_VM" --zone "$DR_ZONE" --format='value(networkInterfaces[0].networkIP)')
# Pod IPs are not masqueraded inside the VPC, so allow the cluster's pod range to reach the VM
PODS=$($G container clusters describe "$CLUSTER" --zone "$ZONE" --format='value(clusterIpv4Cidr)')
$G compute firewall-rules describe edge-dr-from-primary >/dev/null 2>&1 \
  || $G compute firewall-rules create edge-dr-from-primary --network default \
       --allow tcp:8080 --source-ranges "$PODS" --target-tags edge-dr

echo "==> Deploying the edge (images from $REPO)"
$K apply -f k8s/00-namespace.yaml
./scripts/create-secrets.sh "$CONTEXT"
OUT=$(mktemp -d)
for f in k8s/*.yaml; do
  sed -e "s|image: edge-control-plane:dev|image: $REPO/control-plane:$TAG|" \
      -e "s|image: edge-hey:dev|image: $REPO/hey:$TAG|" \
      -e "s|image: edge-streaming:dev|image: $REPO/streaming:$TAG|" "$f" > "$OUT/$(basename "$f")"
done
$K apply -f "$OUT"
rm -rf "$OUT"
# sales fails over to the VM; the env var adds to backend-options in SPRING_APPLICATION_JSON
$K -n edge set env deployment/control-plane EDGE_BACKENDOPTIONS_SALES_FAILOVER="$DR_IP:8080"
$K -n edge rollout status statefulset/postgres --timeout=300s
$K -n edge rollout status deployment/control-plane --timeout=300s
for d in envoy-canary envoy-stable sales tableau; do
  $K -n edge rollout status deployment/$d --timeout=180s
done

echo "==> Done. Failover site at $DR_IP:8080"
echo "    Failover drill: CONTEXT=$CONTEXT ./scripts/failover-demo.sh"
echo "    Delete it all:  ./scripts/gke-down.sh"
