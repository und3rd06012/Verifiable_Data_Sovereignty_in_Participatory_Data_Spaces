import { describe, it, expect, beforeAll } from 'vitest';
import { Device, DutyCycle, syncDevice, resolveConflicts, injectConflict, createRemoteDb } from '@benefits/device-sim';
import { buildDesignDoc } from '@benefits/consent-couchdb';

const DB_NAME = 'e2-live-spike';
const REMOTE_URL = `http://admin:benefits@localhost:5984/${DB_NAME}`;

// Same reason as packages/device-sim/test/fleet.integration.test.ts's own comment:
// describe.skipIf below is evaluated synchronously while the test file is collected,
// before an async beforeAll would run, so the reachability check must be top-level.
let couchDbReachable = false;
try {
  await fetch('http://localhost:5984');
  couchDbReachable = true;
} catch {
  couchDbReachable = false;
}

beforeAll(async () => {
  if (couchDbReachable) {
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

function grant(field: string, purpose = 'service-improvement', recipientClass = 'statistics') {
  return {
    field, purpose, recipientClass,
    grantedAt: '2026-08-01T00:00:00.000Z', expiresAt: null, revokedAt: null
  };
}

describe.skipIf(!couchDbReachable)('e2 live spike', () => {
  it('a device that revokes while offline never leaks the swept record', async () => {
    const participantRef = 'p-spike-revocation';
    const device = new Device(participantRef);
    await device.grantConsent(grant('service.satisfaction'));
    await device.contribute({
      type: 'contribution', participantRef, siteId: 'athens',
      collectedAt: '2026-08-30T11:00:00.000Z', channel: 'app',
      declaredPurpose: 'service-improvement', declaredRecipientClass: 'statistics',
      fields: { 'service.satisfaction': 4 },
      consent: { participantRef, assertedAt: '2026-08-01T00:00:00.000Z', grants: [grant('service.satisfaction')] }
    });
    const revokeResult = await device.revoke('service.satisfaction', 'service-improvement', 'statistics', '2026-08-30T12:00:00.000Z');
    expect(revokeResult.sweptCount).toBe(1);
    expect(await device.unsentRecords()).toHaveLength(0);

    await syncDevice(device, REMOTE_URL);

    const remote = createRemoteDb(REMOTE_URL);
    const allDocs = await remote.allDocs({ include_docs: true });
    const leaked = allDocs.rows.filter((row: any) =>
      row.doc?.participantRef === participantRef && row.doc?.declaredPurpose === 'service-improvement'
    );
    expect(leaked).toHaveLength(0);
  }, 30000);

  it('a device with an injected conflict resolves to its own local content', async () => {
    const participantRef = 'p-spike-conflict';
    const device = new Device(participantRef);
    await device.grantConsent(grant('service.satisfaction'));
    const docId = 'e2-spike-conflict-doc';

    await device.localDb().put({
      _id: docId, type: 'contribution', participantRef, siteId: 'athens',
      collectedAt: '2026-08-30T11:00:00.000Z', channel: 'app',
      declaredPurpose: 'service-improvement', declaredRecipientClass: 'statistics',
      fields: { 'service.satisfaction': 5 }, localEditedAt: '2026-08-30T13:00:00.000Z',
      consent: { participantRef, assertedAt: '2026-08-01T00:00:00.000Z', grants: [grant('service.satisfaction')] }
    });
    await injectConflict(REMOTE_URL, docId, {
      type: 'contribution', participantRef, siteId: 'athens',
      collectedAt: '2026-08-30T11:00:00.000Z', channel: 'app',
      declaredPurpose: 'service-improvement', declaredRecipientClass: 'statistics',
      fields: { 'service.satisfaction': 1 }, localEditedAt: '2026-08-30T15:00:00.000Z',
      consent: { participantRef, assertedAt: '2026-08-01T00:00:00.000Z', grants: [grant('service.satisfaction')] }
    });

    // The brief's literal sequence calls resolveConflicts() here directly,
    // but that leaves the device's own local edit (fields:5) sitting only
    // in its local db: injectConflict() writes a single unrelated revision
    // straight to CouchDB (there is nothing yet on remote for it to
    // conflict with), so remote has exactly one revision and no
    // _conflicts, and resolveConflicts() correctly finds nothing to
    // resolve (verified empirically: resolvedCount was 0, and the fetched
    // remote doc held the *rival's* content, fields:1, not the device's
    // own). packages/device-sim/test/fleet.integration.test.ts's own
    // proven sequence for this exact scenario (device 1) syncs the device
    // to remote AFTER injectConflict() and BEFORE resolveConflicts() --
    // that push is what creates the genuine sibling conflict (two leaf
    // revisions at the same generation, one from the device's local edit,
    // one from the injected rival) for resolveConflicts() to then resolve.
    await syncDevice(device, REMOTE_URL);

    const resolveResult = await resolveConflicts(device, REMOTE_URL);
    expect(resolveResult.resolvedCount).toBe(1);

    const remote = createRemoteDb(REMOTE_URL);
    const doc = await remote.get(docId, { conflicts: true }) as any;
    expect(doc._conflicts ?? []).toEqual([]);
    expect(doc.fields['service.satisfaction']).toBe(5);
  }, 30000);

  it('a baseline device syncs its record to CouchDB unchanged', async () => {
    const participantRef = 'p-spike-baseline';
    const device = new Device(participantRef);
    await device.grantConsent(grant('service.satisfaction'));
    await device.contribute({
      type: 'contribution', participantRef, siteId: 'athens',
      collectedAt: '2026-08-30T11:00:00.000Z', channel: 'app',
      declaredPurpose: 'service-improvement', declaredRecipientClass: 'statistics',
      fields: { 'service.satisfaction': 3 },
      consent: { participantRef, assertedAt: '2026-08-01T00:00:00.000Z', grants: [grant('service.satisfaction')] }
    });

    const syncResult = await syncDevice(device, REMOTE_URL);
    expect(syncResult.pushed).toBe(1);

    const remote = createRemoteDb(REMOTE_URL);
    const allDocs = await remote.allDocs({ include_docs: true });
    const found = allDocs.rows.filter((row: any) => row.doc?.participantRef === participantRef);
    expect(found).toHaveLength(1);
    expect((found[0].doc as any).fields['service.satisfaction']).toBe(3);
  }, 30000);

  it('DutyCycle with a seeded rng produces a deterministic online/offline sequence', () => {
    const values = [0.9, 0.1, 0.9];
    let i = 0;
    const rng = () => values[i++];
    const cycle = new DutyCycle(0.5, rng);
    cycle.tick();
    expect(cycle.isOnline()).toBe(false);
    cycle.tick();
    expect(cycle.isOnline()).toBe(true);
    cycle.tick();
    expect(cycle.isOnline()).toBe(false);
  });
});
