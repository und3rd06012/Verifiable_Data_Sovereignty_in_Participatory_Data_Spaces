#!/usr/bin/env bash
# Gate 0: proves real PouchDB push replication against real CouchDB works,
# and that the document lands validated by consent-couchdb's actual design
# document, before anything else in this phase is built on top of it.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

ensure_couchdb
curl -s -o /dev/null -X DELETE "http://admin:benefits@localhost:5984/benefits-device-sim-probe"
curl -sf -X PUT "http://admin:benefits@localhost:5984/benefits-device-sim-probe" >/dev/null

echo "--- installing packages/device-sim dependencies ---"
npm install --workspace=@benefits/device-sim >/dev/null

echo "--- registering consent-couchdb's real design document on the probe database ---"
(cd "$ROOT/packages/consent-couchdb" && npm run build >/dev/null)
node --input-type=module -e "
import { buildDesignDoc } from '$ROOT/packages/consent-couchdb/dist/build-design-doc.js';
const dd = buildDesignDoc();
const res = await fetch('http://localhost:5984/benefits-device-sim-probe/' + dd._id, {
  method: 'PUT',
  headers: { 'content-type': 'application/json', authorization: 'Basic ' + Buffer.from('admin:benefits').toString('base64') },
  body: JSON.stringify(dd)
});
if (![201, 202].includes(res.status)) { console.error('design doc PUT failed: ' + res.status); process.exit(1); }
console.log('design document installed');
"

echo "--- running the replication probe ---"
node "$ROOT/tools/probe-device-sim-replicate.mjs"
