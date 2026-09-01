import PouchDB from 'pouchdb-core';
import memoryAdapter from 'pouchdb-adapter-memory';
import httpAdapter from 'pouchdb-adapter-http';
import replicationPlugin from 'pouchdb-replication';

PouchDB.plugin(memoryAdapter);
PouchDB.plugin(httpAdapter);
PouchDB.plugin(replicationPlugin);

const local = new PouchDB('probe-local', { adapter: 'memory' });
// Node's native fetch (undici) forbids embedded credentials in a URL, per
// consent-couchdb's own integration test comment. pouchdb-adapter-http does
// not use native fetch, though: it bundles its own client via pouchdb-fetch,
// which resolves to node-fetch, and node-fetch parses userinfo out of the
// URL and turns it into a Basic auth header instead of throwing. Verified
// empirically against a live CouchDB before writing this: embedding
// admin:benefits in the URL works here.
const remote = new PouchDB('http://admin:benefits@localhost:5984/benefits-device-sim-probe');

const doc = {
  _id: 'probe-doc',
  type: 'contribution',
  participantRef: 'probe-participant',
  siteId: 'probe-site',
  collectedAt: new Date().toISOString(),
  channel: 'app',
  declaredPurpose: 'service-improvement',
  declaredRecipientClass: 'statistics',
  fields: { 'service.satisfaction': 4 },
  consent: {
    participantRef: 'probe-participant',
    assertedAt: new Date().toISOString(),
    grants: [{
      field: 'service.satisfaction',
      purpose: 'service-improvement',
      recipientClass: 'statistics',
      grantedAt: '2026-08-01T00:00:00.000Z',
      expiresAt: null,
      revokedAt: null
    }]
  }
};

await local.put(doc);
console.log('put into local (memory adapter) OK');

const result = await new Promise((resolve, reject) => {
  local.replicate.to(remote).on('complete', resolve).on('error', reject);
});
console.log('replicate.to(remote) OK, docs_written=' + result.docs_written);

const fromRemote = await remote.get('probe-doc');
console.log('remote has the document, _rev=' + fromRemote._rev);

if (result.docs_written !== 1) {
  console.error('expected exactly 1 doc written, got ' + result.docs_written);
  process.exit(1);
}
console.log('gate 0 probe passed');
