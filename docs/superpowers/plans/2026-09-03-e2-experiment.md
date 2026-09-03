# E2 Experiment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build E2 (revocation under partition), a per-tick fleet simulation driving the real `packages/device-sim` classes, sweeping connectivity duty cycle and measuring propagation latency, consistency latency, leaked records, and unresolved conflicts.

**Architecture:** A new TypeScript npm workspace, `experiments/harness/e2`, mirroring E1's own structure (not E3/E4/G1/G2's Java/Gradle pattern, since `device-sim` is TypeScript). A single-trial fleet runner drives the real `Device`/`DutyCycle`/`syncDevice`/`resolveConflicts`/`injectConflict` classes against live CouchDB; a sweep-point aggregator pools many trials into one point's statistics; a driver writes the full swept CSV.

**Tech Stack:** TypeScript, npm workspaces, vitest, `@benefits/device-sim`, `@benefits/consent-couchdb`, live CouchDB.

**Spec:** `docs/superpowers/specs/2026-09-03-e2-experiment-design.md`

## Global Constraints

- No em-dashes anywhere: not in code, comments, commit messages, or this plan's own prose.
- No LLM/AI-tooling attribution in commits.
- No emojis in code or logs.
- Seeded determinism only via `mulberry32`, never `Math.random()` (including as `DutyCycle`'s own injectable default, which every call site in this plan must override).
- Do not modify `packages/consent-core`, `packages/consent-couchdb`, or `experiments/harness/e1`'s own already-committed code. `packages/device-sim`'s existing classes (`Device`, `DutyCycle`, `syncDevice`, `resolveConflicts`, `injectConflict`, `createLocalDb`, `createRemoteDb`) are consumed exactly as they exist today; the one exception is Task 1's addition of `packages/device-sim/src/index.ts`, a new file with no existing content to change (see Task 1: this is a packaging-surface fix, not a change to any production class's behavior).
- A live-code-verification spike (Task 2) is required before the trial runner (Task 3) or anything downstream is built.
- A live, reachable CouchDB is required for every task from Task 2 onward. Bring it up via `docker compose -f deploy/docker-compose.yml up -d` and the readiness loop documented in `README.md` (`curl -sf http://admin:benefits@localhost:5984/_up`).

---

## Task 1: `e2` workspace scaffolding, duplicated utilities, the TypeScript `Statistics` module, and the `device-sim` packaging fix

**Files:**
- Create: `packages/device-sim/src/index.ts`
- Create: `experiments/harness/e2/package.json`
- Create: `experiments/harness/e2/tsconfig.json`
- Create: `experiments/harness/e2/src/csv.ts`
- Create: `experiments/harness/e2/src/rng.ts`
- Create: `experiments/harness/e2/src/host-info.ts`
- Create: `experiments/harness/e2/src/statistics.ts`
- Test: `experiments/harness/e2/test/csv.test.ts`
- Test: `experiments/harness/e2/test/rng.test.ts`
- Test: `experiments/harness/e2/test/host-info.test.ts`
- Test: `experiments/harness/e2/test/statistics.test.ts`

**Interfaces:**
- Produces: `writeCsv(path: string, header: string[], rows: (string|number)[][]): void`, `mulberry32(seed: number): () => number`, `randomBytes(rand, length): Uint8Array`, `readHostCpuModel(): string`, `median(values: number[]): number`, `iqr(values: number[]): number`, `percentile(values: number[], p: number): number`. Also: `packages/device-sim`'s `Device`, `DutyCycle`, `syncDevice`, `resolveConflicts`, `injectConflict`, `createLocalDb`, `createRemoteDb` become importable via `@benefits/device-sim` (the package name), not only via relative paths within `device-sim`'s own source tree. All of the above are used by Task 2 onward.

- [ ] **Step 1: Fix the `device-sim` packaging gap**

`packages/device-sim/package.json` declares `"main": "dist/index.js"`, but no `src/index.ts` exists, so that file is never built. No package outside `device-sim`'s own source tree has ever imported it by package name before (its own tests use relative imports like `'../src/device'`); this plan's Task 2 is the first real cross-package consumer, and would hit a broken import immediately without this fix. This is a packaging-surface addition, not a change to any existing class's behavior: every symbol below already exists and is exported from its own file; this file only re-exports them from one place.

`packages/device-sim/src/index.ts`:

```typescript
export { Device } from './device';
export { DutyCycle } from './dutyCycle';
export { syncDevice, resolveConflicts, injectConflict } from './sync';
export { createLocalDb, createRemoteDb } from './pouchdb';
```

Run `npm run build --workspace=packages/device-sim` (or `cd packages/device-sim && npm run build`) and confirm `dist/index.js` and `dist/index.d.ts` now exist.

- [ ] **Step 2: Create the `e2` workspace**

`experiments/harness/e2/package.json`:

```json
{
  "name": "@benefits/experiment-e2",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "build": "tsc -p tsconfig.json",
    "test": "vitest run",
    "sweep": "npm run build && node dist/run-e2.js"
  },
  "dependencies": {
    "@benefits/device-sim": "0.1.0",
    "@benefits/consent-couchdb": "0.1.0"
  }
}
```

`experiments/harness/e2/tsconfig.json`:

```json
{
  "extends": "../../../tsconfig.base.json",
  "compilerOptions": {
    "outDir": "dist",
    "rootDir": "src",
    "target": "ES2020",
    "lib": ["ES2020"]
  },
  "include": ["src"]
}
```

The root `package.json`'s `"workspaces"` field is `["packages/*", "apps/*", "experiments/harness/*"]`, a glob that already covers this new directory; no root file needs editing. Run `npm install` from the repository root so the new workspace is linked.

- [ ] **Step 3: Duplicate the three utility files verbatim from `e1`**

Copy `experiments/harness/e1/src/csv.ts`, `experiments/harness/e1/src/rng.ts`, and
`experiments/harness/e1/src/host-info.ts` to `experiments/harness/e2/src/` unchanged,
byte for byte (no import paths inside these three files reference anything
`e1`-specific, so nothing needs adjusting):

`experiments/harness/e2/src/csv.ts`:

```typescript
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

function csvField(value: string | number): string {
  const str = String(value);
  if (str.includes(',') || str.includes('"') || str.includes('\n')) {
    return '"' + str.replace(/"/g, '""') + '"';
  }
  return str;
}

export function writeCsv(path: string, header: string[], rows: (string | number)[][]): void {
  mkdirSync(dirname(path), { recursive: true });
  const lines = [header.join(',')];
  for (const row of rows) {
    lines.push(row.map(csvField).join(','));
  }
  writeFileSync(path, lines.join('\n') + '\n');
}
```

`experiments/harness/e2/src/rng.ts`:

```typescript
// mulberry32: a standard small, fast, deterministic PRNG. Used only to generate
// synthetic experiment data (record content, device keys) reproducibly from a
// seed, never for anything requiring cryptographic randomness.
export function mulberry32(seed: number): () => number {
  var a = seed >>> 0;
  return function () {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    var t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export function randomBytes(rand: () => number, length: number): Uint8Array {
  var bytes = new Uint8Array(length);
  for (var i = 0; i < length; i++) {
    bytes[i] = Math.floor(rand() * 256);
  }
  return bytes;
}
```

`experiments/harness/e2/src/host-info.ts`:

```typescript
import { readFileSync } from 'node:fs';
import { cpus } from 'node:os';

export function readHostCpuModel(): string {
  try {
    const cpuinfo = readFileSync('/proc/cpuinfo', 'utf8');
    const match = cpuinfo.match(/^model name\s*:\s*(.+)$/m);
    if (match) {
      return match[1].trim();
    }
  } catch (e) {
    // /proc/cpuinfo not present on this platform; fall through to os.cpus().
  }
  const list = cpus();
  return list.length > 0 ? list[0].model : 'unknown';
}
```

- [ ] **Step 4: Write smoke tests for the three duplicated files**

`experiments/harness/e2/test/csv.test.ts`:

```typescript
import { describe, it, expect } from 'vitest';
import { writeCsv } from '../src/csv';
import { readFileSync, mkdtempSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';

describe('writeCsv', () => {
  it('writes a header row and quotes fields containing commas', () => {
    const dir = mkdtempSync(join(tmpdir(), 'e2-csv-'));
    const path = join(dir, 'out.csv');
    writeCsv(path, ['a', 'b'], [[1, 'x,y'], [2, 'z']]);
    const content = readFileSync(path, 'utf8');
    expect(content).toBe('a,b\n1,"x,y"\n2,z\n');
  });
});
```

`experiments/harness/e2/test/rng.test.ts`:

```typescript
import { describe, it, expect } from 'vitest';
import { mulberry32 } from '../src/rng';

describe('mulberry32', () => {
  it('is deterministic for the same seed', () => {
    const a = mulberry32(1);
    const b = mulberry32(1);
    expect(a()).toBe(b());
    expect(a()).toBe(b());
  });

  it('produces values in [0, 1)', () => {
    const rand = mulberry32(42);
    for (let i = 0; i < 100; i++) {
      const v = rand();
      expect(v).toBeGreaterThanOrEqual(0);
      expect(v).toBeLessThan(1);
    }
  });
});
```

`experiments/harness/e2/test/host-info.test.ts`:

```typescript
import { describe, it, expect } from 'vitest';
import { readHostCpuModel } from '../src/host-info';

describe('readHostCpuModel', () => {
  it('returns a non-empty string', () => {
    const model = readHostCpuModel();
    expect(typeof model).toBe('string');
    expect(model.length).toBeGreaterThan(0);
  });
});
```

- [ ] **Step 5: Write the `Statistics` module, a TypeScript port of the Java convention**

No TypeScript equivalent of `experiments/harness/{e3,e4,g1,g2}/.../Statistics.java`'s
median/IQR/percentile convention (linear interpolation between order statistics, the
NumPy default / R type-7 convention) exists anywhere in this repository yet. Port it
directly:

`experiments/harness/e2/src/statistics.ts`:

```typescript
export function median(values: number[]): number {
  return percentile(values, 0.5);
}

export function iqr(values: number[]): number {
  return percentile(values, 0.75) - percentile(values, 0.25);
}

export function percentile(values: number[], p: number): number {
  if (values.length === 0) {
    throw new Error('cannot compute a percentile over zero values');
  }
  const sorted = [...values].sort((a, b) => a - b);
  if (sorted.length === 1) {
    return sorted[0];
  }
  const rank = p * (sorted.length - 1);
  const lowerIndex = Math.floor(rank);
  const upperIndex = Math.ceil(rank);
  if (lowerIndex === upperIndex) {
    return sorted[lowerIndex];
  }
  const fraction = rank - lowerIndex;
  return sorted[lowerIndex] + fraction * (sorted[upperIndex] - sorted[lowerIndex]);
}
```

`experiments/harness/e2/test/statistics.test.ts`:

```typescript
import { describe, it, expect } from 'vitest';
import { median, iqr, percentile } from '../src/statistics';

describe('median', () => {
  it('returns the middle value for an odd count', () => {
    expect(median([1, 3, 2])).toBe(2);
  });

  it('interpolates for an even count', () => {
    expect(median([1, 2, 3, 4])).toBe(2.5);
  });
});

describe('iqr', () => {
  it('matches the known value for a uniform spread', () => {
    expect(iqr([1, 2, 3, 4, 5, 6, 7, 8])).toBeCloseTo(3.5, 9);
  });
});

describe('percentile', () => {
  it('returns the single value for a one-element list', () => {
    expect(percentile([5], 0.9)).toBe(5);
  });

  it('throws for an empty list', () => {
    expect(() => median([])).toThrow();
  });
});
```

- [ ] **Step 6: Run the tests**

Run: `cd experiments/harness/e2 && npm run build && npx vitest run`
Expected: all tests across the four test files pass.

- [ ] **Step 7: Commit**

```bash
git add packages/device-sim/src/index.ts \
  experiments/harness/e2/package.json experiments/harness/e2/tsconfig.json \
  experiments/harness/e2/src/csv.ts experiments/harness/e2/src/rng.ts \
  experiments/harness/e2/src/host-info.ts experiments/harness/e2/src/statistics.ts \
  experiments/harness/e2/test/csv.test.ts experiments/harness/e2/test/rng.test.ts \
  experiments/harness/e2/test/host-info.test.ts experiments/harness/e2/test/statistics.test.ts \
  package-lock.json
git commit -m "feat(e2): add the e2 workspace, duplicated utilities, and the Statistics port"
```

(Include `package-lock.json` only if `npm install` at the repository root actually
changed it; check `git status` first.)

---

## Task 2: Live spike against real `device-sim` classes and live CouchDB

**Files:**
- Test: `experiments/harness/e2/test/live-spike.integration.test.ts`

**Interfaces:**
- Consumes: `Device`, `DutyCycle`, `syncDevice`, `resolveConflicts`, `injectConflict`, `createRemoteDb` (all from `@benefits/device-sim`, via Task 1's new `index.ts`).
- Produces: confirmation that the exact revoke-while-offline, inject-conflict, and baseline-sync sequences this plan's trial runner needs (Task 3/4) behave as the spec describes, against a live CouchDB, with no mocks.

**Read first:** `packages/device-sim/test/fleet.integration.test.ts` in full. It is phase 5's own proven scenario and the direct template for this spike's own setup (the `grant()` helper, the `ContributionRecord` shape, the CouchDB reachability check pattern, the design-doc installation, the exact revoke/inject/sync/resolveConflicts call sequence). Also read `packages/device-sim/test/dutyCycle.test.ts` for how to construct a `DutyCycle` with a hand-controlled sequence of rng values in a test.

- [ ] **Step 1: Write the spike test**

`experiments/harness/e2/test/live-spike.integration.test.ts`:

```typescript
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
```

- [ ] **Step 2: Run the spike**

Confirm CouchDB is reachable (`curl -sf http://admin:benefits@localhost:5984/_up`),
starting it per the Global Constraints section if not. Then run:
`cd experiments/harness/e2 && npm run build && npx vitest run test/live-spike.integration.test.ts`
Expected: all 4 tests pass. If the first import (`@benefits/device-sim`) fails to
resolve at all, Task 1's packaging fix did not work; investigate that first rather
than working around it here.

- [ ] **Step 3: Commit**

```bash
git add experiments/harness/e2/test/live-spike.integration.test.ts
git commit -m "test(e2): add the live spike against the real device-sim classes"
```

---

## Task 3: Single-trial fleet runner, revocation and baseline devices

**Files:**
- Create: `experiments/harness/e2/src/trial.ts`
- Test: `experiments/harness/e2/test/trial.integration.test.ts`

**Interfaces:**
- Consumes: `Device`, `DutyCycle`, `syncDevice`, `createRemoteDb` (`@benefits/device-sim`); `mulberry32` (Task 1).
- Produces: `TrialResult { propagationTicks: number[]; consistencyTicks: number[]; leakedRecordCount: number; unresolvedConflictCount: number; }` and
  `runTrial(seed: number, onlineProbability: number, onlineProbabilityIndex: number, trialIndex: number, remoteUrl: string): Promise<TrialResult>`.
  This task's own version has no conflict devices yet, so `consistencyTicks` is
  always `[]` and `unresolvedConflictCount` is always `0`; Task 4 extends this
  same function to add the conflict role. Used by Task 4 (extended) and Task 5
  (called many times per sweep point).

- [ ] **Step 1: Write the failing test**

`experiments/harness/e2/test/trial.integration.test.ts`:

```typescript
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd experiments/harness/e2 && npm run build && npx vitest run test/trial.integration.test.ts`
Expected: FAIL, `runTrial` does not exist.

- [ ] **Step 3: Implement `runTrial` (revocation and baseline devices only)**

`experiments/harness/e2/src/trial.ts`:

```typescript
import { Device, DutyCycle, syncDevice, createRemoteDb } from '@benefits/device-sim';
import { mulberry32 } from './rng';

const MAX_TICKS = 200;
const REVOCATION_COUNT = 3;
const BASELINE_COUNT = 4;
const FLEET_SIZE = REVOCATION_COUNT + BASELINE_COUNT;

function grant(field: string, purpose = 'service-improvement', recipientClass = 'statistics') {
  return {
    field, purpose, recipientClass,
    grantedAt: '2026-08-01T00:00:00.000Z', expiresAt: null, revokedAt: null
  };
}

export interface TrialResult {
  propagationTicks: number[];
  consistencyTicks: number[];
  leakedRecordCount: number;
  unresolvedConflictCount: number;
}

type Role = 'revocation' | 'baseline';

interface RoleDevice {
  participantRef: string;
  device: Device;
  role: Role;
  cycle: DutyCycle;
  done: boolean;
}

export async function runTrial(
  seed: number,
  onlineProbability: number,
  onlineProbabilityIndex: number,
  trialIndex: number,
  remoteUrl: string
): Promise<TrialResult> {
  const rng = mulberry32(seed * 1_000_003 + onlineProbabilityIndex * 1_000 + trialIndex);
  const nowIso = '2026-08-30T12:00:00.000Z';

  const devices: RoleDevice[] = [];
  for (let i = 0; i < FLEET_SIZE; i++) {
    const role: Role = i < REVOCATION_COUNT ? 'revocation' : 'baseline';
    const participantRef = `p-e2-${seed}-${onlineProbabilityIndex}-${trialIndex}-${i}`;
    const device = new Device(participantRef);
    await device.grantConsent(grant('service.satisfaction'));
    devices.push({ participantRef, device, role, cycle: new DutyCycle(onlineProbability, rng), done: false });
  }

  const revocationDevices = devices.filter((d) => d.role === 'revocation');
  const baselineDevices = devices.filter((d) => d.role === 'baseline');

  for (const entry of revocationDevices) {
    await entry.device.contribute({
      type: 'contribution', participantRef: entry.participantRef, siteId: 'athens',
      collectedAt: nowIso, channel: 'app',
      declaredPurpose: 'service-improvement', declaredRecipientClass: 'statistics',
      fields: { 'service.satisfaction': 4 },
      consent: { participantRef: entry.participantRef, assertedAt: '2026-08-01T00:00:00.000Z', grants: [grant('service.satisfaction')] }
    });
    await entry.device.revoke('service.satisfaction', 'service-improvement', 'statistics', nowIso);
  }

  for (const entry of baselineDevices) {
    await entry.device.contribute({
      type: 'contribution', participantRef: entry.participantRef, siteId: 'athens',
      collectedAt: nowIso, channel: 'app',
      declaredPurpose: 'service-improvement', declaredRecipientClass: 'statistics',
      fields: { 'service.satisfaction': 3 },
      consent: { participantRef: entry.participantRef, assertedAt: '2026-08-01T00:00:00.000Z', grants: [grant('service.satisfaction')] }
    });
  }

  const propagationTicks: number[] = [];

  for (let tick = 1; tick <= MAX_TICKS; tick++) {
    for (const entry of devices) {
      if (!entry.done) {
        entry.cycle.tick();
      }
    }
    for (const entry of devices) {
      if (entry.done || !entry.cycle.isOnline()) {
        continue;
      }
      await syncDevice(entry.device, remoteUrl);
      if (entry.role === 'revocation') {
        propagationTicks.push(tick);
      }
      entry.done = true;
    }
    if (devices.every((d) => d.done)) {
      break;
    }
  }

  const notDone = devices.filter((d) => !d.done);
  if (notDone.length > 0) {
    throw new Error(
      `${notDone.length} device(s) never came online within ${MAX_TICKS} ticks at onlineProbability=${onlineProbability}`
    );
  }

  const remote = createRemoteDb(remoteUrl);
  const allDocs = await remote.allDocs({ include_docs: true });
  let leakedRecordCount = 0;
  for (const entry of revocationDevices) {
    const leaked = allDocs.rows.filter((row: any) =>
      row.doc?.participantRef === entry.participantRef && row.doc?.declaredPurpose === 'service-improvement'
    );
    leakedRecordCount += leaked.length;
  }

  return { propagationTicks, consistencyTicks: [], leakedRecordCount, unresolvedConflictCount: 0 };
}
```

- [ ] **Step 4: Run the tests and verify they pass**

Run: `cd experiments/harness/e2 && npm run build && npx vitest run test/trial.integration.test.ts`
Expected: all 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add experiments/harness/e2/src/trial.ts experiments/harness/e2/test/trial.integration.test.ts
git commit -m "feat(e2): add the trial runner, revocation and baseline devices"
```

---

## Task 4: Add conflict devices to the trial runner

**Files:**
- Modify: `experiments/harness/e2/src/trial.ts`
- Modify: `experiments/harness/e2/test/trial.integration.test.ts`

**Interfaces:**
- Consumes: `resolveConflicts`, `injectConflict` (`@benefits/device-sim`, newly used in this task).
- Produces: `runTrial(...)` now returns real, non-empty `consistencyTicks` and a real `unresolvedConflictCount`. Used by Task 5.

- [ ] **Step 1: Add failing tests for the conflict role**

Add to `experiments/harness/e2/test/trial.integration.test.ts`:

```typescript
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
```

- [ ] **Step 2: Run the tests to verify the new ones fail**

Run: `cd experiments/harness/e2 && npm run build && npx vitest run test/trial.integration.test.ts`
Expected: the two new tests FAIL (`consistencyTicks` is always `[]` from Task 3).

- [ ] **Step 3: Extend `runTrial` with the conflict role**

Modify `experiments/harness/e2/src/trial.ts`. Change the import line:

```typescript
import { Device, DutyCycle, syncDevice, resolveConflicts, injectConflict, createRemoteDb } from '@benefits/device-sim';
```

Add the conflict count constant and update the fleet size:

```typescript
const REVOCATION_COUNT = 3;
const CONFLICT_COUNT = 3;
const BASELINE_COUNT = 4;
const FLEET_SIZE = REVOCATION_COUNT + CONFLICT_COUNT + BASELINE_COUNT;
```

Change `Role` and `RoleDevice`:

```typescript
type Role = 'revocation' | 'conflict' | 'baseline';

interface RoleDevice {
  participantRef: string;
  device: Device;
  role: Role;
  cycle: DutyCycle;
  done: boolean;
  docId?: string;
}
```

In the fleet-construction loop, change the role assignment:

```typescript
    const role: Role = i < REVOCATION_COUNT ? 'revocation'
      : i < REVOCATION_COUNT + CONFLICT_COUNT ? 'conflict' : 'baseline';
```

After the `revocationDevices`/`baselineDevices` declarations, add:

```typescript
  const conflictDevices = devices.filter((d) => d.role === 'conflict');
```

After the revocation devices' setup loop and before the baseline devices' setup
loop, add the conflict devices' tick-0 setup:

```typescript
  for (const entry of conflictDevices) {
    entry.docId = `e2-conflict-${entry.participantRef}`;
    await entry.device.localDb().put({
      _id: entry.docId, type: 'contribution', participantRef: entry.participantRef, siteId: 'athens',
      collectedAt: nowIso, channel: 'app',
      declaredPurpose: 'service-improvement', declaredRecipientClass: 'statistics',
      fields: { 'service.satisfaction': 5 }, localEditedAt: nowIso,
      consent: { participantRef: entry.participantRef, assertedAt: '2026-08-01T00:00:00.000Z', grants: [grant('service.satisfaction')] }
    });
    await injectConflict(remoteUrl, entry.docId, {
      type: 'contribution', participantRef: entry.participantRef, siteId: 'athens',
      collectedAt: nowIso, channel: 'app',
      declaredPurpose: 'service-improvement', declaredRecipientClass: 'statistics',
      fields: { 'service.satisfaction': 1 }, localEditedAt: '2026-08-30T15:00:00.000Z',
      consent: { participantRef: entry.participantRef, assertedAt: '2026-08-01T00:00:00.000Z', grants: [grant('service.satisfaction')] }
    });
  }
```

Add `const consistencyTicks: number[] = [];` alongside the existing
`const propagationTicks: number[] = [];`.

Replace the tick loop's per-device action block:

```typescript
    for (const entry of devices) {
      if (entry.done || !entry.cycle.isOnline()) {
        continue;
      }
      if (entry.role === 'conflict') {
        await resolveConflicts(entry.device, remoteUrl);
        consistencyTicks.push(tick);
      } else {
        await syncDevice(entry.device, remoteUrl);
        if (entry.role === 'revocation') {
          propagationTicks.push(tick);
        }
      }
      entry.done = true;
    }
```

After the leaked-record check, before the final `return`, add the
unresolved-conflict check:

```typescript
  let unresolvedConflictCount = 0;
  for (const entry of conflictDevices) {
    const doc = await remote.get(entry.docId!, { conflicts: true }) as any;
    if ((doc._conflicts ?? []).length > 0) {
      unresolvedConflictCount++;
    }
  }
```

Change the final `return` statement:

```typescript
  return { propagationTicks, consistencyTicks, leakedRecordCount, unresolvedConflictCount };
```

- [ ] **Step 4: Run the tests and verify all pass**

Run: `cd experiments/harness/e2 && npm run build && npx vitest run test/trial.integration.test.ts`
Expected: all tests pass, including Task 3's original 3 tests (still using the same
seed/index arguments, now exercising a 10-device fleet instead of 7, but their
assertions only ever checked the revocation-side fields, which are unaffected by
adding the conflict role).

- [ ] **Step 5: Commit**

```bash
git add experiments/harness/e2/src/trial.ts experiments/harness/e2/test/trial.integration.test.ts
git commit -m "feat(e2): add conflict devices to the trial runner"
```

---

## Task 5: Sweep-point aggregator

**Files:**
- Create: `experiments/harness/e2/src/sweep-point.ts`
- Test: `experiments/harness/e2/test/sweep-point.integration.test.ts`

**Interfaces:**
- Consumes: `runTrial` (Task 4), `median`/`iqr` (Task 1).
- Produces: `SweepPointResult` and `runSweepPoint(seed, onlineProbability, onlineProbabilityIndex, trials, remoteUrl): Promise<SweepPointResult>`. Used by Task 6.

- [ ] **Step 1: Write the failing test**

`experiments/harness/e2/test/sweep-point.integration.test.ts`:

```typescript
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd experiments/harness/e2 && npm run build && npx vitest run test/sweep-point.integration.test.ts`
Expected: FAIL, `runSweepPoint` does not exist.

- [ ] **Step 3: Implement `runSweepPoint`**

`experiments/harness/e2/src/sweep-point.ts`:

```typescript
import { runTrial } from './trial';
import { median, iqr } from './statistics';

export interface SweepPointResult {
  seed: number;
  onlineProbability: number;
  trials: number;
  propagationMedianTicks: number;
  propagationIqrTicks: number;
  propagationDataPoints: number;
  consistencyMedianTicks: number;
  consistencyIqrTicks: number;
  consistencyDataPoints: number;
  leakedRecordCount: number;
  unresolvedConflictCount: number;
}

export async function runSweepPoint(
  seed: number,
  onlineProbability: number,
  onlineProbabilityIndex: number,
  trials: number,
  remoteUrl: string
): Promise<SweepPointResult> {
  const propagationTicks: number[] = [];
  const consistencyTicks: number[] = [];
  let leakedRecordCount = 0;
  let unresolvedConflictCount = 0;

  for (let trialIndex = 0; trialIndex < trials; trialIndex++) {
    const result = await runTrial(seed, onlineProbability, onlineProbabilityIndex, trialIndex, remoteUrl);
    propagationTicks.push(...result.propagationTicks);
    consistencyTicks.push(...result.consistencyTicks);
    leakedRecordCount += result.leakedRecordCount;
    unresolvedConflictCount += result.unresolvedConflictCount;
  }

  return {
    seed,
    onlineProbability,
    trials,
    propagationMedianTicks: median(propagationTicks),
    propagationIqrTicks: iqr(propagationTicks),
    propagationDataPoints: propagationTicks.length,
    consistencyMedianTicks: median(consistencyTicks),
    consistencyIqrTicks: iqr(consistencyTicks),
    consistencyDataPoints: consistencyTicks.length,
    leakedRecordCount,
    unresolvedConflictCount
  };
}
```

- [ ] **Step 4: Run the tests and verify they pass**

Run: `cd experiments/harness/e2 && npm run build && npx vitest run test/sweep-point.integration.test.ts`
Expected: both tests pass.

- [ ] **Step 5: Commit**

```bash
git add experiments/harness/e2/src/sweep-point.ts experiments/harness/e2/test/sweep-point.integration.test.ts
git commit -m "feat(e2): add the sweep point aggregator"
```

---

## Task 6: Sweep driver, CSV writer, production entry point, and the real production run

**Files:**
- Create: `experiments/harness/e2/src/sweep.ts`
- Create: `experiments/harness/e2/src/run-e2.ts`
- Test: `experiments/harness/e2/test/sweep.integration.test.ts`

**Interfaces:**
- Consumes: `runSweepPoint` (Task 5), `writeCsv` and `readHostCpuModel` (Task 1).
- Produces: `experiments/results/e2/partition.csv`, the sub-project's committed output.

- [ ] **Step 1: Write the failing test**

`experiments/harness/e2/test/sweep.integration.test.ts`:

```typescript
import { describe, it, expect } from 'vitest';
import { runSweep } from '../src/sweep';
import { readFileSync, existsSync, mkdtempSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';

let couchDbReachable = false;
try {
  await fetch('http://localhost:5984');
  couchDbReachable = true;
} catch {
  couchDbReachable = false;
}

describe.skipIf(!couchDbReachable)('runSweep', () => {
  it('writes one header row plus one row per swept onlineProbability value', async () => {
    const outDir = mkdtempSync(join(tmpdir(), 'e2-sweep-'));
    await runSweep({
      dbName: 'e2-sweep-driver-test',
      seed: 1,
      onlineProbabilities: [0.5, 0.9],
      trials: 3,
      outDir
    });

    const path = join(outDir, 'partition.csv');
    expect(existsSync(path)).toBe(true);
    const lines = readFileSync(path, 'utf8').trim().split('\n');
    expect(lines).toHaveLength(3); // header + 2 sweep points
    expect(lines[0]).toBe(
      'seed,online_probability,trials,propagation_median_ticks,propagation_iqr_ticks,' +
      'propagation_data_points,consistency_median_ticks,consistency_iqr_ticks,' +
      'consistency_data_points,leaked_record_count,unresolved_conflict_count,' +
      'host_cpu_model,throttle_cpus,throttle_memory_mb'
    );
  }, 120000);
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd experiments/harness/e2 && npm run build && npx vitest run test/sweep.integration.test.ts`
Expected: FAIL, `runSweep` does not exist.

- [ ] **Step 3: Implement `runSweep`**

`experiments/harness/e2/src/sweep.ts`:

```typescript
import { runSweepPoint } from './sweep-point';
import { readHostCpuModel } from './host-info';
import { writeCsv } from './csv';
import { buildDesignDoc } from '@benefits/consent-couchdb';
import { join } from 'node:path';

export interface SweepOptions {
  dbName: string;
  seed: number;
  onlineProbabilities: number[];
  trials: number;
  outDir: string;
  throttleCpus?: number;
  throttleMemoryMb?: number;
}

export async function runSweep(opts: SweepOptions): Promise<void> {
  const authHeader = { authorization: 'Basic ' + Buffer.from('admin:benefits').toString('base64') };
  const base = 'http://localhost:5984';
  await fetch(`${base}/${opts.dbName}`, { method: 'DELETE', headers: authHeader });
  await fetch(`${base}/${opts.dbName}`, { method: 'PUT', headers: authHeader });
  const dd = buildDesignDoc();
  await fetch(`${base}/${opts.dbName}/${dd._id}`, {
    method: 'PUT',
    headers: { ...authHeader, 'content-type': 'application/json' },
    body: JSON.stringify(dd)
  });

  const remoteUrl = `http://admin:benefits@localhost:5984/${opts.dbName}`;
  const hostCpuModel = readHostCpuModel();
  const throttleCpus = opts.throttleCpus ?? '';
  const throttleMemoryMb = opts.throttleMemoryMb ?? '';

  const rows: (string | number)[][] = [];
  for (let i = 0; i < opts.onlineProbabilities.length; i++) {
    const onlineProbability = opts.onlineProbabilities[i];
    const result = await runSweepPoint(opts.seed, onlineProbability, i, opts.trials, remoteUrl);
    rows.push([
      result.seed,
      result.onlineProbability,
      result.trials,
      result.propagationMedianTicks,
      result.propagationIqrTicks,
      result.propagationDataPoints,
      result.consistencyMedianTicks,
      result.consistencyIqrTicks,
      result.consistencyDataPoints,
      result.leakedRecordCount,
      result.unresolvedConflictCount,
      hostCpuModel,
      throttleCpus,
      throttleMemoryMb
    ]);
  }

  writeCsv(
    join(opts.outDir, 'partition.csv'),
    [
      'seed', 'online_probability', 'trials',
      'propagation_median_ticks', 'propagation_iqr_ticks', 'propagation_data_points',
      'consistency_median_ticks', 'consistency_iqr_ticks', 'consistency_data_points',
      'leaked_record_count', 'unresolved_conflict_count',
      'host_cpu_model', 'throttle_cpus', 'throttle_memory_mb'
    ],
    rows
  );
}
```

- [ ] **Step 4: Run the tests and verify they pass**

Run: `cd experiments/harness/e2 && npm run build && npx vitest run test/sweep.integration.test.ts`
Expected: passes.

- [ ] **Step 5: Write the production entry point**

`experiments/harness/e2/src/run-e2.ts`:

```typescript
import { runSweep } from './sweep.js';

runSweep({
  dbName: 'e2-sweep',
  seed: 1,
  onlineProbabilities: [0.05, 0.1, 0.3, 0.5, 0.9],
  trials: 30,
  outDir: '../../results/e2',
  throttleCpus: 1,
  throttleMemoryMb: 1024
}).then(() => {
  console.log('E2 sweep complete');
}).catch((err) => {
  console.error(err);
  process.exitCode = 1;
});
```

- [ ] **Step 6: Run the full production sweep and verify reproducibility**

```bash
cd experiments/harness/e2
npm run build
node dist/run-e2.js
```

This produces `experiments/results/e2/partition.csv`. Copy that file aside (e.g.
`cp experiments/results/e2/partition.csv /tmp/e2-run-1.csv`), then run the sweep a
second time the same way (`node dist/run-e2.js` again, same `dbName` and `seed`,
which will overwrite `experiments/results/e2/partition.csv` with a fresh run's
output), and `diff /tmp/e2-run-1.csv experiments/results/e2/partition.csv`.
Expected: 6 lines (1 header + 5 sweep points) in both files. The `diff` must show
no differences. Every row's `leaked_record_count` and `unresolved_conflict_count`
must be exactly `0`; if either is ever nonzero, stop and investigate as a real
defect in the trial runner (Task 3/4), not something to report as-is (spec section
5's own framing: these are correctness gates, not statistics with an expected
distribution).

- [ ] **Step 7: Commit the code and the real results**

```bash
git add experiments/harness/e2/src/sweep.ts experiments/harness/e2/src/run-e2.ts \
  experiments/harness/e2/test/sweep.integration.test.ts
git commit -m "feat(e2): add the sweep driver and production entry point"
git add -f experiments/results/e2/partition.csv
git commit -m "feat(e2): commit the real production partition sweep results"
```

---

## Final verification (part of the last task's own checklist, not a separate task)

Before the whole-plan final review, run the full repository test suite once more
to confirm nothing outside `e2` (and the additive `packages/device-sim/src/index.ts`)
regressed:

```bash
npm run build --workspaces --if-present
npm run test --workspaces --if-present
```

Expected: every workspace's tests pass, including `packages/device-sim` (Task 1's
new `index.ts` is additive only, so its own existing tests must be unaffected) and
`experiments/harness/e1` (entirely untouched, read-only reference for this plan's
own duplicated utilities).
