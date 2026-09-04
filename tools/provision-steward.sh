#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib.sh"

STEWARD_ID=${1:?usage: provision-steward.sh <steward-id> [password]}
PASSWORD=${2:-$(openssl rand -base64 24)}
COUCHDB_URL=${COUCHDB_URL:-http://localhost:5984}
COUCHDB_ADMIN_USER=${COUCHDB_ADMIN_USER:-admin}
COUCHDB_ADMIN_PASSWORD=${COUCHDB_ADMIN_PASSWORD:-benefits}

STATUS=$(curl -s -o /tmp/provision-steward-response.json -w "%{http_code}" \
  -u "$COUCHDB_ADMIN_USER:$COUCHDB_ADMIN_PASSWORD" \
  -X PUT "$COUCHDB_URL/_users/org.couchdb.user:$STEWARD_ID" \
  -H "content-type: application/json" \
  -d "{\"name\":\"$STEWARD_ID\",\"password\":\"$PASSWORD\",\"roles\":[\"steward\"],\"type\":\"user\"}")

if [ "$STATUS" = "201" ]; then
  echo "steward provisioned: $STEWARD_ID"
  echo "password: $PASSWORD"
elif [ "$STATUS" = "409" ]; then
  echo "steward $STEWARD_ID already exists; password NOT changed (CouchDB requires the current _rev to update an existing user document -- re-run is not a rotation)"
else
  echo "failed to provision steward $STEWARD_ID: HTTP $STATUS" >&2
  cat /tmp/provision-steward-response.json >&2
  exit 1
fi


# optional: enroll TOTP through the auth gateway so the first login already
# requires a second factor. Set GATEWAY_URL (e.g. https://host/api) to use it.
if [ -n "${GATEWAY_URL:-}" ] && curl -skf "$GATEWAY_URL/healthz" >/dev/null 2>&1; then
  ENROLL=$(curl -sk -X POST "$GATEWAY_URL/auth/enroll" -H 'content-type: application/json'     -d "{\"username\":\"$STEWARD_ID\",\"password\":\"$PASSWORD\",\"method\":\"totp\"}")
  SECRET=$(printf '%s' "$ENROLL" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("secret",""))' 2>/dev/null || true)
  URI=$(printf '%s' "$ENROLL" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("otpauthUri",""))' 2>/dev/null || true)
  if [ -n "$SECRET" ]; then
    echo "TOTP secret:     $SECRET"
    echo "TOTP otpauth URI: $URI"
  fi
fi
