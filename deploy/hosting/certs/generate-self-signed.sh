#!/usr/bin/env bash
# Generates a self-signed certificate for the demo. For a public deployment
# replace this with Let's Encrypt (see README).
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$DIR"
openssl req -x509 -nodes -newkey rsa:2048 -days 90 \
  -keyout "$DIR/privkey.pem" \
  -out "$DIR/fullchain.pem" \
  -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
echo "self-signed cert written to $DIR"
