#!/usr/bin/env bash
# Generate a self-signed TLS certificate for local rehearsal / intranet use.
# Output: deploy/certs/server.crt + server.key (mounted read-only into nginx).
set -euo pipefail
cd "$(dirname "$0")"

openssl req -x509 -newkey rsa:2048 -nodes -days 825 \
  -keyout server.key -out server.crt \
  -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"

chmod 600 server.key
echo "Generated:"
echo "  $(pwd)/server.crt"
echo "  $(pwd)/server.key"
