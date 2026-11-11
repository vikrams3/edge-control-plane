#!/usr/bin/env bash
# Self-signed certificate for *.edge.local, for HTTPS on the Envoys' :10443 listener.
# Writes certs/tls.crt and certs/tls.key (git-ignored). Demo only: browsers will warn.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p certs
openssl req -x509 -newkey rsa:2048 -nodes -days "${DAYS:-365}" \
  -subj "/CN=*.edge.local" -addext "subjectAltName=DNS:*.edge.local,DNS:edge.local" \
  -keyout certs/tls.key -out certs/tls.crt 2>/dev/null
echo "Wrote certs/tls.crt and certs/tls.key ($(openssl x509 -in certs/tls.crt -noout -enddate))"
