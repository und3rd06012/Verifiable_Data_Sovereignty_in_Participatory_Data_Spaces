#!/usr/bin/env bash
# Creates a demo participant in CouchDB: a _users document, an isolated
# participant-<ref> database with _security restricting writes to that
# participant, and the consent validate_doc_update design document (S1).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

PARTICIPANT_REF=${1:?usage: provision-participant.sh <participant-ref> [password]}
PASSWORD=${2:-$(openssl rand -base64 18)}
COUCHDB_URL=${COUCHDB_URL:-http://localhost:5984}
ADMIN_USER=${COUCHDB_ADMIN_USER:-admin}
ADMIN_PASSWORD=${COUCHDB_ADMIN_PASSWORD:-benefits}
BASE="$COUCHDB_URL"
DB="participant-$PARTICIPANT_REF"

curl -sf "$BASE/_up" >/dev/null || { echo "CouchDB is not reachable at $BASE" >&2; exit 1; }

# 1. user document
STATUS=$(curl -s -o /tmp/provision-participant-user.json -w "%{http_code}" \
  -u "$ADMIN_USER:$ADMIN_PASSWORD" \
  -X PUT "$BASE/_users/org.couchdb.user:$PARTICIPANT_REF" \
  -H "content-type: application/json" \
  -d "{\"name\":\"$PARTICIPANT_REF\",\"password\":\"$PASSWORD\",\"roles\":[\"participant\"],\"type\":\"user\"}")

if [ "$STATUS" = "201" ]; then
  echo "participant user created: $PARTICIPANT_REF"
elif [ "$STATUS" = "409" ]; then
  echo "participant user $PARTICIPANT_REF already exists; password NOT changed"
else
  echo "failed to create participant user $PARTICIPANT_REF: HTTP $STATUS" >&2
  cat /tmp/provision-participant-user.json >&2
  exit 1
fi

# 2. isolated database
curl -s -X PUT -u "$ADMIN_USER:$ADMIN_PASSWORD" "$BASE/$DB" >/dev/null
echo "created database $DB"

# 3. restrict writes to this participant (admins and replicators keep access)
curl -s -X PUT -u "$ADMIN_USER:$ADMIN_PASSWORD" "$BASE/$DB/_security" \
  -H "content-type: application/json" \
  -d "{\"members\":{\"names\":[\"$PARTICIPANT_REF\"],\"roles\":[\"participant\"]},\"admins\":{\"names\":[],\"roles\":[\"_admin\"]}}" >/dev/null
echo "set _security on $DB"

# 4. install the consent validator (S1) from the prebuilt design-doc JSON
DD_JSON="$ROOT/packages/consent-couchdb/design-doc.json"
if [ -f "$DD_JSON" ]; then
  DD_ID=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["_id"])' "$DD_JSON")
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" -u "$ADMIN_USER:$ADMIN_PASSWORD" -X PUT "$BASE/$DB/$DD_ID" -H 'content-type: application/json' --data-binary "@$DD_JSON")
  echo "installed consent validator from design-doc.json: $STATUS"
else
  node --input-type=module -e "
import { buildDesignDoc } from '$ROOT/packages/consent-couchdb/dist/build-design-doc.js';
const dd = buildDesignDoc();
const res = await fetch('$BASE/$DB/' + dd._id, {
  method: 'PUT',
  headers: { 'content-type': 'application/json',
             authorization: 'Basic ' + Buffer.from('$ADMIN_USER:$ADMIN_PASSWORD').toString('base64') },
  body: JSON.stringify(dd)
});
console.log('installed consent validator:', res.status);
"
fi

echo
echo "participant ready:"
echo "  ref:      $PARTICIPANT_REF"
echo "  password: $PASSWORD"
echo "  database: $DB"


# optional: enroll TOTP through the auth gateway so the first login already
# requires a second factor. Set GATEWAY_URL (e.g. https://host/api) to use it.
if [ -n "${GATEWAY_URL:-}" ] && curl -skf "$GATEWAY_URL/healthz" >/dev/null 2>&1; then
  ENROLL=$(curl -sk -X POST "$GATEWAY_URL/auth/enroll" -H 'content-type: application/json'     -d "{\"username\":\"$PARTICIPANT_REF\",\"password\":\"$PASSWORD\",\"method\":\"totp\"}")
  SECRET=$(printf '%s' "$ENROLL" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("secret",""))' 2>/dev/null || true)
  URI=$(printf '%s' "$ENROLL" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("otpauthUri",""))' 2>/dev/null || true)
  if [ -n "$SECRET" ]; then
    echo "TOTP secret:     $SECRET"
    echo "TOTP otpauth URI: $URI"
  fi
fi
