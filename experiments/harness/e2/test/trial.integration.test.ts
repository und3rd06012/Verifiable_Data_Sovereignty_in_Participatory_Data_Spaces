import { describe, it, expect, beforeAll } from 'vitest';
import { runTrial } from '../src/trial';
import { buildDesignDoc } from '@benefits/consent-couchdb';

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

  it('produces no conflict data yet (conflict devices are Task 4)', async () => {
    const result = await runTrial(1, 0.9, 0, 1, REMOTE_URL);
    expect(result.consistencyTicks).toEqual([]);
    expect(result.unresolvedConflictCount).toBe(0);
  }, 30000);

  it('same seed, onlineProbability, and indices produce the same propagation ticks', async () => {
    const a = await runTrial(7, 0.5, 2, 5, REMOTE_URL);
    const b = await runTrial(7, 0.5, 2, 5, REMOTE_URL);
    expect(a.propagationTicks).toEqual(b.propagationTicks);
  }, 30000);
});
