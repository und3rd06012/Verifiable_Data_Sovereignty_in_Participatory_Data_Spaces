import { describe, it, expect, beforeAll } from 'vitest';
import { Device } from '../src/device';
import { syncDevice, injectConflict, resolveConflicts } from '../src/sync';
import { createRemoteDb } from '../src/pouchdb';
import { buildDesignDoc } from '@benefits/consent-couchdb';

const REMOTE_BASE = 'http://admin:benefits@localhost:5984';
const DB_NAME = 'benefits-device-sim-sync-test';
const REMOTE_URL = `${REMOTE_BASE}/${DB_NAME}`;

// `describe.skipIf` below is evaluated synchronously while the test file
// is collected -- well before an async `beforeAll` hook would ever run --
// so the reachability check has to happen here, via top-level await, not
// inside `beforeAll`. (Confirmed empirically: with the check only inside
// `beforeAll`, both tests report "skipped" even with CouchDB reachable
// and answering on :5984, because `couchDbReachable` is still its initial
// `false` at the moment `describe.skipIf` reads it.)
let couchDbReachable = false;
try {
  await fetch('http://localhost:5984');
  couchDbReachable = true;
} catch {
  couchDbReachable = false;
}

beforeAll(async () => {
  if (couchDbReachable) {
    // CouchDB 3.x requires admin auth to DELETE a database -- an
    // unauthenticated DELETE 401s and is silently ignored (fetch does not
    // throw on a non-2xx status), leaving a stale database from the
    // previous run in place for the following PUT (which then 412s,
    // itself unchecked). Confirmed empirically: without this header, the
    // "conflict-doc-*" fixtures accumulate extra revisions across runs
    // instead of starting clean each time.
    const authHeader = { authorization: 'Basic ' + Buffer.from('admin:benefits').toString('base64') };
    await fetch(`http://localhost:5984/${DB_NAME}`, { method: 'DELETE', headers: authHeader });
    await fetch(`http://localhost:5984/${DB_NAME}`, { method: 'PUT', headers: authHeader });
    const dd = buildDesignDoc();
    await fetch(`http://localhost:5984/${DB_NAME}/${dd._id}`, {
      method: 'PUT',
      headers: { ...authHeader, 'content-type': 'application/json' },
      body: JSON.stringify(dd)
    });
  }
}, 30000);

function grant(field: string) {
  return {
    field, purpose: 'service-improvement', recipientClass: 'statistics',
    grantedAt: '2026-08-01T00:00:00.000Z', expiresAt: null, revokedAt: null
  };
}

describe.skipIf(!couchDbReachable)('syncDevice', () => {
  it('pushes unsent records to the remote database', async () => {
    const device = new Device('p-sync-1');
    await device.grantConsent(grant('service.satisfaction'));
    await device.contribute({
      type: 'contribution', participantRef: 'p-sync-1', siteId: 'athens',
      collectedAt: '2026-08-30T11:00:00.000Z', channel: 'app',
      declaredPurpose: 'service-improvement', declaredRecipientClass: 'statistics',
      fields: { 'service.satisfaction': 4 },
      consent: { participantRef: 'p-sync-1', assertedAt: '2026-08-01T00:00:00.000Z', grants: [grant('service.satisfaction')] }
    });

    const result = await syncDevice(device, REMOTE_URL);
    expect(result.pushed).toBe(1);
  });

  it('does not retroactively delete an already-synced record when a later revoke sweeps the outbox', async () => {
    const device = new Device('p-no-retro-delete');
    await device.grantConsent(grant('service.satisfaction'));
    await device.contribute({
      type: 'contribution', participantRef: 'p-no-retro-delete', siteId: 'athens',
      collectedAt: '2026-08-30T11:00:00.000Z', channel: 'app',
      declaredPurpose: 'service-improvement', declaredRecipientClass: 'statistics',
      fields: { 'service.satisfaction': 4 },
      consent: { participantRef: 'p-no-retro-delete', assertedAt: '2026-08-01T00:00:00.000Z', grants: [grant('service.satisfaction')] }
    });

    await syncDevice(device, REMOTE_URL);

    const { sweptCount } = await device.revoke('service.satisfaction', 'service-improvement', 'statistics', '2026-08-30T12:00:00.000Z');
    expect(sweptCount).toBe(0);

    await syncDevice(device, REMOTE_URL);

    const remote = createRemoteDb(REMOTE_URL);
    const allDocs = await remote.allDocs({ include_docs: true });
    const survivingRecord = allDocs.rows.find(
      (row) => (row.doc as unknown as { participantRef?: string })?.participantRef === 'p-no-retro-delete'
    );
    expect(survivingRecord).toBeDefined();
  });

  it('resolveConflicts keeps the device\'s own local edit even when an injected rival claims a LATER edit time', async () => {
    const device = new Device('p-sync-2');
    const docId = 'conflict-doc-1';
    await device.localDb().put({
      _id: docId, type: 'contribution', participantRef: 'p-sync-2', siteId: 'athens',
      collectedAt: '2026-08-30T11:00:00.000Z', channel: 'app',
      declaredPurpose: 'service-improvement', declaredRecipientClass: 'statistics',
      fields: { 'service.satisfaction': 4 }, localEditedAt: '2026-08-30T13:00:00.000Z',
      consent: { participantRef: 'p-sync-2', assertedAt: '2026-08-01T00:00:00.000Z', grants: [grant('service.satisfaction')] }
    });

    // Simulate the partition: a rival revision lands on the server, claiming
    // a LATER localEditedAt than the device's own edit -- deliberately, to
    // prove resolution is identity-based, not a last-write-wins comparison.
    // If this test used an earlier rival timestamp instead, it would pass
    // under either algorithm and prove nothing about which one is actually
    // implemented (spec section 5's precision note exists because an
    // earlier draft's code did exactly that, undetected, until a task
    // reviewer traced the discrepancy against the spec's prose).
    await injectConflict(REMOTE_URL, docId, {
      type: 'contribution', participantRef: 'p-sync-2', siteId: 'athens',
      collectedAt: '2026-08-30T11:00:00.000Z', channel: 'app',
      declaredPurpose: 'service-improvement', declaredRecipientClass: 'statistics',
      fields: { 'service.satisfaction': 999 }, localEditedAt: '2026-08-30T14:00:00.000Z',
      consent: { participantRef: 'p-sync-2', assertedAt: '2026-08-01T00:00:00.000Z', grants: [grant('service.satisfaction')] }
    });

    await syncDevice(device, REMOTE_URL);
    const resolved = await resolveConflicts(device, REMOTE_URL);
    expect(resolved.resolvedCount).toBe(1);

    const remoteDb = createRemoteDb(REMOTE_URL);
    const finalDoc = await remoteDb.get(docId) as unknown as { fields: { 'service.satisfaction': number }; _conflicts?: string[] };
    expect(finalDoc.fields['service.satisfaction']).toBe(4);

    const withConflicts = await remoteDb.get(docId, { conflicts: true }) as unknown as { _conflicts?: string[] };
    expect(withConflicts._conflicts ?? []).toEqual([]);
  });

  // Distinguishes "resolveConflicts genuinely consults the device's own
  // local database" from the shortcut of trusting whichever revision
  // CouchDB's own (identity-blind) deterministic winner-selection already
  // promoted to `_rev` / `row.doc` -- the exact accident-of-revision-
  // ordering risk called out in this task's self-review. Unlike the
  // primary conflict test above (where which sibling CouchDB happens to
  // pick as "current" is effectively hash-random, since both revisions
  // land at the same generation), this test forces the two to disagree
  // *deterministically*: the rival is injected only after the device has
  // already synced, so conflictInjector's generation-following bogus
  // revision lands one generation ABOVE the device's -- CouchDB's native
  // winner rule (highest generation wins) is guaranteed to promote the
  // rival by default. A resolver that read its content from `row.doc`
  // (CouchDB's default pick) instead of from `device.localDb()` would
  // keep the rival's content and fail this test.
  it('resolveConflicts overrides CouchDB\'s own default winner with the device\'s own local content', async () => {
    const device = new Device('p-sync-3');
    const docId = 'conflict-doc-2';
    await device.localDb().put({
      _id: docId, type: 'contribution', participantRef: 'p-sync-3', siteId: 'athens',
      collectedAt: '2026-08-30T11:00:00.000Z', channel: 'app',
      declaredPurpose: 'service-improvement', declaredRecipientClass: 'statistics',
      fields: { 'service.satisfaction': 4 }, localEditedAt: '2026-08-30T13:00:00.000Z',
      consent: { participantRef: 'p-sync-3', assertedAt: '2026-08-01T00:00:00.000Z', grants: [grant('service.satisfaction')] }
    });

    // Sync first: the device's own revision is the sole (uncontested)
    // remote revision before the rival ever lands.
    await syncDevice(device, REMOTE_URL);

    // Inject the rival AFTER the device has already synced -- conflictInjector
    // reads the current (device's) revision's generation and writes its
    // sibling one generation higher, which CouchDB's native winner rule
    // picks as "current" by default. Its claimed localEditedAt is later
    // still, reinforcing that neither timestamp nor CouchDB's own
    // ordering should decide the outcome -- only identity should.
    await injectConflict(REMOTE_URL, docId, {
      type: 'contribution', participantRef: 'p-sync-3', siteId: 'athens',
      collectedAt: '2026-08-30T11:00:00.000Z', channel: 'app',
      declaredPurpose: 'service-improvement', declaredRecipientClass: 'statistics',
      fields: { 'service.satisfaction': 999 }, localEditedAt: '2026-08-30T15:00:00.000Z',
      consent: { participantRef: 'p-sync-3', assertedAt: '2026-08-01T00:00:00.000Z', grants: [grant('service.satisfaction')] }
    });

    // Sanity check: confirm CouchDB's own default pick right now really is
    // the rival, not the device's edit -- proving this test actually
    // exercises the override, rather than passing by a coincidence of
    // matching orderings.
    const remoteDbBefore = createRemoteDb(REMOTE_URL);
    const beforeResolve = await remoteDbBefore.get(docId) as unknown as { fields: { 'service.satisfaction': number } };
    expect(beforeResolve.fields['service.satisfaction']).toBe(999);

    const resolved = await resolveConflicts(device, REMOTE_URL);
    expect(resolved.resolvedCount).toBe(1);

    const remoteDb = createRemoteDb(REMOTE_URL);
    const finalDoc = await remoteDb.get(docId) as unknown as { fields: { 'service.satisfaction': number } };
    expect(finalDoc.fields['service.satisfaction']).toBe(4);

    const withConflicts = await remoteDb.get(docId, { conflicts: true }) as unknown as { _conflicts?: string[] };
    expect(withConflicts._conflicts ?? []).toEqual([]);
  });
});
