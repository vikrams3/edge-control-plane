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

# mTLS for the xDS channel (control plane <-> Envoy): a private CA, a server certificate for the
# control plane and a client certificate for the Envoys. Used on kind; Docker Compose stays plaintext.
mkdir -p certs/xds
X=certs/xds
openssl req -x509 -newkey rsa:2048 -nodes -days "${DAYS:-365}" -subj "/CN=edge xDS CA" \
  -keyout $X/ca.key -out $X/ca.crt 2>/dev/null
issue() {   # name, subject CN, subjectAltName, extendedKeyUsage
  openssl req -newkey rsa:2048 -nodes -subj "/CN=$2" -keyout $X/$1.key -out $X/$1.csr 2>/dev/null
  printf 'subjectAltName=%s\nextendedKeyUsage=%s\n' "$3" "$4" > $X/$1.ext
  openssl x509 -req -in $X/$1.csr -CA $X/ca.crt -CAkey $X/ca.key -CAcreateserial \
    -days "${DAYS:-365}" -extfile $X/$1.ext -out $X/$1.crt 2>/dev/null
  rm -f $X/$1.csr $X/$1.ext
}
issue server "control-plane" \
  "DNS:control-plane,DNS:control-plane.edge.svc.cluster.local,DNS:localhost" serverAuth
issue client "envoy" "DNS:envoy" clientAuth
echo "Wrote certs/xds/{ca,server,client}.{crt,key} for mTLS on the xDS channel"
