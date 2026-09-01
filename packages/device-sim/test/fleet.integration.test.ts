import { describe, it, expect, beforeAll } from 'vitest';
import { Device } from '../src/device';
import { DutyCycle } from '../src/dutyCycle';
import { syncDevice, injectConflict, resolveConflicts } from '../src/sync';
import { createRemoteDb } from '../src/pouchdb';

const DB_NAME = 'benefits-device-sim-fleet-test';
const REMOTE_URL = `http://admin:benefits@localhost:5984/${DB_NAME}`;

// `describe.skipIf` below is evaluated synchronously while the test file is
// collected -- well before an async `beforeAll` hook would ever run -- so
// the reachability check has to happen here, via top-level await, not
// inside `beforeAll` (same fix as sync.integration.test.ts's own comment
// on this: with the check only inside `beforeAll`, the suite reports
// "skipped" even with CouchDB reachable, because `couchDbReachable` is
// still its initial `false` at the moment `describe.skipIf` reads it).
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
  }
}, 30000);

function grant(field: string, purpose = 'service-improvement', recipientClass = 'statistics') {
  return {
    field, purpose, recipientClass,
    grantedAt: '2026-08-01T00:00:00.000Z', expiresAt: null, revokedAt: null
  };
}

describe.skipIf(!couchDbReachable)('device fleet end to end', () => {
  it('a fleet with mid-partition revocations and an injected conflict ends with zero leaks and zero unresolved conflicts', async () => {
    const FLEET_SIZE = 6;
    const devices: Device[] = [];
    const cycles: DutyCycle[] = [];
    const rng = (() => {
      const values = [0.1, 0.9, 0.2, 0.8, 0.3, 0.7, 0.4, 0.6, 0.15, 0.85];
      let i = 0;
      return () => values[i++ % values.length];
    })();

    for (let i = 0; i < FLEET_SIZE; i++) {
      const participantRef = `p-fleet-${i}`;
      const device = new Device(participantRef);
      await device.grantConsent(grant('service.satisfaction'));
      devices.push(device);
      cycles.push(new DutyCycle(0.5, rng));
    }

    // Device 0 (offline): queues a record, then revokes while still offline.
    // The record must never reach CouchDB.
    //
    // It also holds a second, independent grant on the SAME field but a
    // DIFFERENT purpose ('advocacy' instead of 'service-improvement') and
    // queues a second record under that purpose. This proves the sweep
    // matches the full (field, purpose, recipientClass) triple, not just
    // the field name -- the specific over-sweep scenario the leak-prevention
    // rule's global constraint calls out (reviewer finding from Task 6:
    // its own tests covered "field absent -> not swept" but not "field
    // present under a different purpose/recipientClass -> not swept").
    await devices[0].grantConsent(grant('service.satisfaction', 'advocacy'));
    await devices[0].contribute({
      type: 'contribution', participantRef: 'p-fleet-0', siteId: 'athens',
      collectedAt: '2026-08-30T11:00:00.000Z', channel: 'app',
      declaredPurpose: 'service-improvement', declaredRecipientClass: 'statistics',
      fields: { 'service.satisfaction': 4 },
      consent: { participantRef: 'p-fleet-0', assertedAt: '2026-08-01T00:00:00.000Z', grants: [grant('service.satisfaction')] }
    });
    await devices[0].contribute({
      type: 'contribution', participantRef: 'p-fleet-0', siteId: 'athens',
      collectedAt: '2026-08-30T11:00:00.000Z', channel: 'app',
      declaredPurpose: 'advocacy', declaredRecipientClass: 'statistics',
      fields: { 'service.satisfaction': 9 },
      consent: { participantRef: 'p-fleet-0', assertedAt: '2026-08-01T00:00:00.000Z', grants: [grant('service.satisfaction', 'advocacy')] }
    });
    const revokeResult = await devices[0].revoke('service.satisfaction', 'service-improvement', 'statistics', '2026-08-30T12:00:00.000Z');
    expect(revokeResult.sweptCount).toBe(1);

    // The over-sweep record (different purpose, same field) must still be
    // sitting in the device's own outbox right after revoke, before any
    // sync happens.
    const stillQueued = await devices[0].unsentRecords();
    expect(stillQueued).toHaveLength(1);
    expect(stillQueued[0].declaredPurpose).toBe('advocacy');
    expect(stillQueued[0].fields['service.satisfaction']).toBe(9);

    // Device 1: an injected conflict, exactly as Task 7 proved works. The
    // rival's localEditedAt is deliberately LATER than the device's own
    // edit (same reasoning as Task 7's discriminating test) -- resolution
    // is identity-based, not last-write-wins, so the device's edit must
    // still win here even though the rival "claims" to be more recent.
    const conflictDocId = 'fleet-conflict-doc';
    await devices[1].localDb().put({
      _id: conflictDocId, type: 'contribution', participantRef: 'p-fleet-1', siteId: 'athens',
      collectedAt: '2026-08-30T11:00:00.000Z', channel: 'app',
      declaredPurpose: 'service-improvement', declaredRecipientClass: 'statistics',
      fields: { 'service.satisfaction': 5 }, localEditedAt: '2026-08-30T13:00:00.000Z',
      consent: { participantRef: 'p-fleet-1', assertedAt: '2026-08-01T00:00:00.000Z', grants: [grant('service.satisfaction')] }
    });
    await injectConflict(REMOTE_URL, conflictDocId, {
      type: 'contribution', participantRef: 'p-fleet-1', siteId: 'athens',
      collectedAt: '2026-08-30T11:00:00.000Z', channel: 'app',
      declaredPurpose: 'service-improvement', declaredRecipientClass: 'statistics',
      fields: { 'service.satisfaction': 1 }, localEditedAt: '2026-08-30T15:00:00.000Z',
      consent: { participantRef: 'p-fleet-1', assertedAt: '2026-08-01T00:00:00.000Z', grants: [grant('service.satisfaction')] }
    });

    // Devices 2-5: ordinary contributions, no revocation, no conflict.
    for (let i = 2; i < FLEET_SIZE; i++) {
      await devices[i].contribute({
        type: 'contribution', participantRef: `p-fleet-${i}`, siteId: 'athens',
        collectedAt: '2026-08-30T11:00:00.000Z', channel: 'app',
        declaredPurpose: 'service-improvement', declaredRecipientClass: 'statistics',
        fields: { 'service.satisfaction': i },
        consent: { participantRef: `p-fleet-${i}`, assertedAt: '2026-08-01T00:00:00.000Z', grants: [grant('service.satisfaction')] }
      });
    }

    // Every device eventually comes online and syncs -- the duty cycle
    // drives whether each device is online at a given tick, but this gate
    // proves the end state after everyone has had a chance to sync, not
    // the timing itself (phase 7's job).
    for (const device of devices) {
      await syncDevice(device, REMOTE_URL);
    }
    await resolveConflicts(devices[1], REMOTE_URL);

    const remote = createRemoteDb(REMOTE_URL);
    const allRemoteDocs = await remote.allDocs({ include_docs: true, conflicts: true });

    // Zero leaked records: device 0's revoked-and-swept record (the
    // service-improvement one) must not appear anywhere in CouchDB. Filtered
    // by declaredPurpose too, now that device 0 contributes two records
    // sharing the same participantRef -- the swept one and the over-sweep
    // one below, which is expected to reach CouchDB.
    const leaked = allRemoteDocs.rows.filter((row) => {
      const doc = row.doc as unknown as { participantRef?: string; declaredPurpose?: string };
      return doc?.participantRef === 'p-fleet-0' && doc?.declaredPurpose === 'service-improvement';
    });
    expect(leaked).toHaveLength(0);

    // The over-sweep record (same field, different purpose) was correctly
    // NOT swept, and did reach CouchDB after sync.
    const notSwept = allRemoteDocs.rows.filter((row) => {
      const doc = row.doc as unknown as { participantRef?: string; declaredPurpose?: string };
      return doc?.participantRef === 'p-fleet-0' && doc?.declaredPurpose === 'advocacy';
    });
    expect(notSwept).toHaveLength(1);
    expect((notSwept[0].doc as unknown as { fields: { 'service.satisfaction': number } }).fields['service.satisfaction']).toBe(9);

    // Zero unresolved conflicts: every document CouchDB holds has an empty
    // _conflicts list after resolution.
    for (const row of allRemoteDocs.rows) {
      const doc = row.doc as unknown as { _conflicts?: string[] };
      expect(doc._conflicts ?? []).toEqual([]);
    }

    // The conflict device's document reflects its own local edit (fields:5),
    // not the injected rival (fields:1) -- user preference first, end to end.
    const conflictDoc = await remote.get(conflictDocId) as unknown as { fields: { 'service.satisfaction': number } };
    expect(conflictDoc.fields['service.satisfaction']).toBe(5);
  }, 30000);
});
