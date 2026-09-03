import { describe, it, expect, beforeAll } from 'vitest';
import { runSweepPoint } from '../src/sweep-point';
import { buildDesignDoc } from '@benefits/consent-couchdb';

const DB_NAME = 'e2-sweep-point-test';
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

describe.skipIf(!couchDbReachable)('runSweepPoint', () => {
  it('pools propagation and consistency ticks across every trial, with zero leaks', async () => {
    const result = await runSweepPoint(1, 0.9, 0, 5, REMOTE_URL);
    expect(result.trials).toBe(5);
    expect(result.propagationDataPoints).toBe(15); // 5 trials x 3 revocation devices
    expect(result.consistencyDataPoints).toBe(15); // 5 trials x 3 conflict devices
    expect(result.propagationMedianTicks).toBeGreaterThanOrEqual(1);
    expect(result.consistencyMedianTicks).toBeGreaterThanOrEqual(1);
    expect(result.leakedRecordCount).toBe(0);
    expect(result.unresolvedConflictCount).toBe(0);
  }, 60000);

  it('a lower onlineProbability produces a higher (or equal) median propagation tick count', async () => {
    const slow = await runSweepPoint(2, 0.1, 1, 5, REMOTE_URL);
    const fast = await runSweepPoint(2, 0.9, 2, 5, REMOTE_URL);
    expect(slow.propagationMedianTicks).toBeGreaterThanOrEqual(fast.propagationMedianTicks);
  }, 60000);
});
