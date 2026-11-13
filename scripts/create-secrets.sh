#!/usr/bin/env bash
# Creates the edge namespace's TLS secrets from certs/ (generating the certs on first use):
# the HTTPS certificate Envoy serves over SDS, and the mTLS certificates for xDS.
#   ./scripts/create-secrets.sh [kubectl context]
set -euo pipefail
cd "$(dirname "$0")/.."
K="kubectl ${1:+--context $1}"
[ -f certs/tls.crt ] && [ -f certs/xds/ca.crt ] || ./scripts/gen-certs.sh
$K -n edge create secret tls edge-tls --cert=certs/tls.crt --key=certs/tls.key \
  --dry-run=client -o yaml | $K apply -f -
$K -n edge create secret generic xds-server-tls --from-file=ca.crt=certs/xds/ca.crt \
  --from-file=server.crt=certs/xds/server.crt --from-file=server.key=certs/xds/server.key \
  --dry-run=client -o yaml | $K apply -f -
$K -n edge create secret generic xds-client-tls --from-file=ca.crt=certs/xds/ca.crt \
  --from-file=client.crt=certs/xds/client.crt --from-file=client.key=certs/xds/client.key \
  --dry-run=client -o yaml | $K apply -f -
