#!/usr/bin/env bash
# Deletes everything scripts/gke-up.sh created, so nothing keeps billing.
set -euo pipefail
PROJECT=${PROJECT:-$(gcloud config get-value project 2>/dev/null)}
REGION=${REGION:-us-central1}
ZONE=${ZONE:-us-central1-a}
DR_ZONE=${DR_ZONE:-us-east1-b}
G="gcloud --project $PROJECT --quiet"

# Delete the namespace first: that deletes Postgres's PVC, so GKE also deletes its disk
# (deleting the cluster alone leaves the disk behind, billing)
kubectl --context gke-primary delete namespace edge --timeout=180s 2>/dev/null || true
$G container clusters delete edge-primary --zone "$ZONE" --async 2>/dev/null || true
$G compute instances delete edge-dr-sales --zone "$DR_ZONE" 2>/dev/null || true
$G compute firewall-rules delete edge-dr-from-primary 2>/dev/null || true
$G artifacts repositories delete edge --location "$REGION" 2>/dev/null || true
kubectl config delete-context gke-primary >/dev/null 2>&1 || true

echo "==> Deleting; the cluster takes a few minutes. Left behind, if any (should be empty soon):"
$G container clusters list --format='value(name,status)'
$G compute instances list --format='value(name,status)'
# Disks GKE made for PersistentVolumes (Postgres) are deleted with the cluster's PVCs only if
# the namespace is deleted first; list any stragglers so they can be removed by hand
$G compute disks list --format='value(name,zone)'
