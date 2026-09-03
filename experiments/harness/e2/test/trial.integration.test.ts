import { describe, it, expect, beforeAll } from 'vitest';
import { runTrial } from '../src/trial';
import { buildDesignDoc } from '@benefits/consent-couchdb';
import { createRemoteDb } from '@benefits/device-sim';

const DB_NAME = 'e2-trial-test';
const REMOTE_URL = `http://admin:benefits@localhost:5984/${DB_NAME}`;

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

describe.skipIf(!couchDbReachable)('runTrial (revocation and baseline devices)', () => {
  it('every revocation device gets a propagation tick and leaks nothing', async () => {
    const result = await runTrial(1, 0.9, 0, 0, REMOTE_URL);
    expect(result.propagationTicks).toHaveLength(3);
    for (const ticks of result.propagationTicks) {
      expect(ticks).toBeGreaterThanOrEqual(1);
    }
    expect(result.leakedRecordCount).toBe(0);
  }, 30000);

  it('produces consistency ticks and resolves conflicts cleanly (conflict devices added in Task 4)', async () => {
    const result = await runTrial(1, 0.9, 0, 1, REMOTE_URL);
    expect(result.consistencyTicks).toHaveLength(3);
    expect(result.unresolvedConflictCount).toBe(0);
  }, 30000);

  it('same seed, onlineProbability, and indices produce the same propagation ticks', async () => {
    const a = await runTrial(7, 0.5, 2, 5, REMOTE_URL);
    const b = await runTrial(7, 0.5, 2, 5, REMOTE_URL);
    expect(a.propagationTicks).toEqual(b.propagationTicks);
  }, 30000);

  it('every conflict device gets a consistency tick and resolves cleanly', async () => {
    const result = await runTrial(1, 0.9, 0, 2, REMOTE_URL);
    expect(result.consistencyTicks).toHaveLength(3);
    for (const ticks of result.consistencyTicks) {
      expect(ticks).toBeGreaterThanOrEqual(1);
    }
    expect(result.unresolvedConflictCount).toBe(0);
  }, 30000);

  it('propagation ticks are still produced alongside consistency ticks in the same trial', async () => {
    const result = await runTrial(1, 0.9, 0, 3, REMOTE_URL);
    expect(result.propagationTicks).toHaveLength(3);
    expect(result.consistencyTicks).toHaveLength(3);
  }, 30000);

  it('a resolved conflict device document reflects its own local edit, not the injected rival', async () => {
    // Deliberately checks the WINNING CONTENT on remote, not just that
    // unresolvedConflictCount is 0. That check alone cannot distinguish
    // "a real conflict was created and correctly resolved" from "no real
    // conflict was ever created in the first place" (both leave remote's
    // _conflicts array empty). Task 2's own live spike found exactly this
    // failure mode live: without a syncDevice() call before resolveConflicts()
    // for a conflict device, injectConflict's rival becomes the ONLY remote
    // revision (no sibling to conflict with), resolveConflicts silently
    // finds nothing to resolve, and unresolvedConflictCount stays 0 even
    // though the device's own edit never won. This test asserts the actual
    // field value (5, the device's own local edit set up in trial.ts) beat
    // the rival's (1), which only holds if a genuine conflict existed and
    // was genuinely resolved.
    const seed = 1;
    const onlineProbability = 0.9;
    const onlineProbabilityIndex = 0;
    const trialIndex = 4;
    await runTrial(seed, onlineProbability, onlineProbabilityIndex, trialIndex, REMOTE_URL);

    // Device index 3 is the first conflict device (indices 0-2 are
    // revocation, 3-5 are conflict, matching REVOCATION_COUNT=3 then
    // CONFLICT_COUNT=3 in trial.ts), so its participantRef and docId follow
    // exactly the same construction trial.ts itself uses.
    const participantRef = `p-e2-${seed}-${onlineProbabilityIndex}-${trialIndex}-3`;
    const docId = `e2-conflict-${participantRef}`;
    const remote = createRemoteDb(REMOTE_URL);
    const doc = await remote.get(docId) as any;
    expect(doc.fields['service.satisfaction']).toBe(5);
  }, 30000);
});
