#!/usr/bin/env bash
# Runs unit and integration tests (mvn verify): *IT.java start a real Envoy and a real
# PostgreSQL with Testcontainers. Maven runs in a container with the Docker socket mounted,
# so only Docker is needed. Dependencies are cached in the "edge-m2" volume.
# Containers that Testcontainers starts are siblings, not children, so their published ports
# are reached through host.docker.internal (Docker Desktop, or host-gateway on Linux).
set -euo pipefail
cd "$(dirname "$0")/../control-plane"
docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$PWD":/src -w /src \
  -v edge-m2:/root/.m2 \
  -e TESTCONTAINERS_RYUK_DISABLED="${TESTCONTAINERS_RYUK_DISABLED:-false}" \
  --add-host=host.docker.internal:host-gateway \
  -e TESTCONTAINERS_HOST_OVERRIDE="${TESTCONTAINERS_HOST_OVERRIDE:-host.docker.internal}" \
  maven:3.9-eclipse-temurin-21 \
  mvn -B -ntp verify -Dspring.main.banner-mode=off "$@"
