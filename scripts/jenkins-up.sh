#!/usr/bin/env bash
# Starts a local Jenkins (http://localhost:8090, admin/admin) that builds this repo's main
# branch, runs the integration tests, deploys to the kind cluster and smoke-tests it.
#   ./scripts/jenkins-up.sh          # start Jenkins and run the pipeline once
#   NO_BUILD=1 ./scripts/jenkins-up.sh
#   SIMULATE_SMOKE_FAILURE=1 ./scripts/jenkins-up.sh   # exercise the automatic rollback
# Jenkins builds committed code only: it clones this repo from a read-only mount.
set -euo pipefail
cd "$(dirname "$0")/.."
REPO=$PWD
CI=$REPO/.ci                      # workspaces live here, at the same path inside Jenkins
mkdir -p "$CI/workspace"

kind get clusters | grep -qx edge || { echo "Run ./scripts/kind-up.sh first"; exit 1; }
kind get kubeconfig --internal --name edge > "$CI/kubeconfig"

echo "==> Building the Jenkins image"
docker build -q -t edge-jenkins:dev jenkins >/dev/null

docker rm -f edge-jenkins >/dev/null 2>&1 || true
echo "==> Starting Jenkins on http://localhost:8090 (admin/admin)"
# The Docker socket lets the pipeline build images and start Testcontainers. Those containers
# are siblings on the host's Docker, so a bind mount from the workspace must use the same
# path on both sides: hence $CI mounted at $CI and the workspaces dir pointed into it.
docker run -d --name edge-jenkins --network kind -p 8090:8080 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$REPO":/repo:ro \
  -v "$CI":"$CI" \
  -v "$CI/kubeconfig":/root/.kube/config:ro \
  -v edge-jenkins-home:/var/jenkins_home \
  -e JAVA_OPTS="-Djenkins.install.runSetupWizard=false -Dhudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT=true -Djenkins.model.Jenkins.workspacesDir=$CI/workspace/\${ITEM_FULL_NAME}" \
  edge-jenkins:dev >/dev/null

J=http://localhost:8090
until curl -sf -u admin:admin "$J/job/edge-control-plane/api/json" >/dev/null; do sleep 3; done
echo "    Jenkins is up; job edge-control-plane exists"
[ "${NO_BUILD:-}" = 1 ] && exit 0

echo "==> Running the pipeline on $(git rev-parse --short HEAD)"
JAR=$(mktemp); trap 'rm -f "$JAR"' EXIT
CRUMB=$(curl -s -c "$JAR" -u admin:admin "$J/crumbIssuer/api/json" \
  | python3 -c 'import json,sys; c=json.load(sys.stdin); print(c["crumbRequestField"]+":"+c["crumb"])')
NEXT=$(curl -s -u admin:admin "$J/job/edge-control-plane/api/json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["nextBuildNumber"])')
if [ "${SIMULATE_SMOKE_FAILURE:-0}" = 1 ]; then
  curl -sf -b "$JAR" -u admin:admin -H "$CRUMB" -X POST \
    "$J/job/edge-control-plane/buildWithParameters?SIMULATE_SMOKE_FAILURE=true" >/dev/null
else
  # buildWithParameters also works before the job has seen its parameters (first build)
  curl -sf -b "$JAR" -u admin:admin -H "$CRUMB" -X POST "$J/job/edge-control-plane/buildWithParameters" >/dev/null \
    || curl -sf -b "$JAR" -u admin:admin -H "$CRUMB" -X POST "$J/job/edge-control-plane/build" >/dev/null
fi
until curl -sf -u admin:admin "$J/job/edge-control-plane/$NEXT/api/json" >/dev/null; do sleep 2; done
while [ "$(curl -s -u admin:admin "$J/job/edge-control-plane/$NEXT/api/json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["building"])')" = True ]; do sleep 5; done
curl -s -u admin:admin "$J/job/edge-control-plane/$NEXT/consoleText" \
  | grep -E '^\[Pipeline\] \{ \(|  (ok|FAIL)|Tests run: [0-9]+, F[^-]*$|Finished:|is live|Smoke test|rolling|DEPLOYMENT|edge-(control-plane|analytics|streaming):' || true
RESULT=$(curl -s -u admin:admin "$J/job/edge-control-plane/$NEXT/api/json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["result"])')
echo "==> Build #$NEXT: $RESULT   ($J/job/edge-control-plane/$NEXT/)"
[ "$RESULT" = SUCCESS ]
