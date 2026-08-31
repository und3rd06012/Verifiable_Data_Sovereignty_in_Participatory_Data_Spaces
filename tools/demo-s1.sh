#!/usr/bin/env bash
# Posts a record carrying a field that no consent grant covers, and shows
# CouchDB refusing it from inside its own validation function.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE=http://admin:benefits@localhost:5984
DB=benefits-demo

curl -sf "$BASE/_up" >/dev/null || { echo "CouchDB is not reachable at localhost:5984" >&2; exit 1; }

curl -s -X DELETE "$BASE/$DB" >/dev/null
curl -sf -X PUT "$BASE/$DB" >/dev/null
echo "created database $DB"

node --input-type=module -e "
import { buildDesignDoc } from '$ROOT/packages/consent-couchdb/dist/build-design-doc.js';
const dd = buildDesignDoc();
const res = await fetch('http://localhost:5984/$DB/' + dd._id, {
  method: 'PUT',
  headers: { 'content-type': 'application/json',
             authorization: 'Basic ' + Buffer.from('admin:benefits').toString('base64') },
  body: JSON.stringify(dd)
});
console.log('installed consent validator:', res.status);
"

echo
echo "--- posting a record whose only grant covers service.satisfaction ---"
echo "--- but which also carries health.condition, which nothing grants ---"
curl -s -X POST "$BASE/$DB" -H 'content-type: application/json' -d '{
  "type": "contribution",
  "participantRef": "p-001",
  "siteId": "athens",
  "collectedAt": "2026-08-01T00:00:00.000Z",
  "channel": "app",
  "declaredPurpose": "service-improvement",
  "declaredRecipientClass": "statistics",
  "fields": { "service.satisfaction": 4, "health.condition": "asthma" },
  "consent": {
    "participantRef": "p-001",
    "assertedAt": "2026-08-01T00:00:00.000Z",
    "grants": [{
      "field": "service.satisfaction",
      "purpose": "service-improvement",
      "recipientClass": "statistics",
      "grantedAt": "2026-08-01T00:00:00.000Z",
      "expiresAt": null
    }]
  }
}'
echo
echo
echo "--- the same record without the ungranted field is accepted ---"
curl -s -X POST "$BASE/$DB" -H 'content-type: application/json' -d '{
  "type": "contribution",
  "participantRef": "p-001",
  "siteId": "athens",
  "collectedAt": "2026-08-01T00:00:00.000Z",
  "channel": "app",
  "declaredPurpose": "service-improvement",
  "declaredRecipientClass": "statistics",
  "fields": { "service.satisfaction": 4 },
  "consent": {
    "participantRef": "p-001",
    "assertedAt": "2026-08-01T00:00:00.000Z",
    "grants": [{
      "field": "service.satisfaction",
      "purpose": "service-improvement",
      "recipientClass": "statistics",
      "grantedAt": "2026-08-01T00:00:00.000Z",
      "expiresAt": null
    }]
  }
}'
echo
curl -s -X DELETE "$BASE/$DB" >/dev/null
echo
echo "cleaned up database $DB"
