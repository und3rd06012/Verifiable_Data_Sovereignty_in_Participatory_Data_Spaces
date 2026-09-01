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
