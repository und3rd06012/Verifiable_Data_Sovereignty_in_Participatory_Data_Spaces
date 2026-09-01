# BENEFITS Phase 5: Device Fleet Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a simulated fleet of offline-first participant devices (real PouchDB, real duty cycles, real replication against real CouchDB) that proves consent revocation propagates correctly under partition with zero leaked records, and that a controlled, injected replication conflict resolves under a "user preference first" rule — the mechanism experiment E2 measures.

**Architecture:** A small addition to phase 0's `packages/consent-core` (a `revokedAt` field, mirroring the existing `expiresAt` semantics exactly) plus one new package, `packages/device-sim`, containing a `Device` class (local PouchDB, an outbox, online/offline state, a revoke action), a duty-cycle scheduler, harness-side conflict injection (a direct write to CouchDB bypassing the device, since a single-writer device has nothing to organically conflict with), and a "keep my own latest edit" conflict resolver.

**Tech Stack:** TypeScript 5.7.2, vitest, PouchDB (`pouchdb-core` + `pouchdb-adapter-memory` + `pouchdb-adapter-http` + `pouchdb-replication`, composed explicitly rather than the batteries-included `pouchdb` package, which defaults to a LevelDB/native backend on Node this phase does not need), real CouchDB via Docker (`deploy/docker-compose.yml`, already exists).

**Spec:** docs/superpowers/specs/2026-09-01-benefits-device-fleet-design.md

## Global Constraints

- `ConsentGrant.revokedAt` is `string | null`, required (not optional), evaluated by `hasGrant` exactly the way `expiresAt` already is: `revokedAt !== null && revokedAt <= nowIso` makes the grant inactive.
- `meet()` combines `revokedAt` via the same `earliest()` helper `expiresAt` already uses — earliest (most restrictive) wins.
- All 15 existing conformance vectors get `"revokedAt": null` added to every grant; this must not change any existing vector's `expected` value.
- The in-memory PouchDB adapter (`pouchdb-adapter-memory`) is required for every simulated device — no LevelDB, no IndexedDB, no native dependency.
- "Leaked record" is defined precisely (spec section 4): a record that was in a device's own outbox, unsent, at the moment of revocation, whose embedded consent covers the revoked grant, and that later reaches CouchDB anyway. Records already synced before revocation are never touched or re-checked.
- Conflicts are produced only by harness-side direct writes to CouchDB (spec section 5) — never by simulating a second device per participant.
- "User preference first" conflict resolution: the device's own most recent local edit wins outright, whole-document, tracked independently of PouchDB's own revision ordering.
- No comments beyond a non-obvious WHY.

---

### Task 1: Gate 0 — PouchDB replication probe

**Files:**
- Create: `packages/device-sim/package.json`
- Create: `packages/device-sim/tsconfig.json`
- Create: `packages/device-sim/src/pouchdb.ts`
- Create: `tools/probe-device-sim-replication.sh`
- Create: `tools/probe-device-sim-replicate.mjs`

**Interfaces:**
- Produces: `createLocalDb(name: string): PouchDB.Database` and `createRemoteDb(url: string): PouchDB.Database`, both from `src/pouchdb.ts` — every later task's `Device` class is built on these two functions.

This is a de-risking probe, in the same spirit as every prior phase's Task 1: no PouchDB dependency exists in this repo yet. Prove real push replication against real CouchDB works, and that a document replicated this way is validated by `consent-couchdb`'s actual design document (not a mock), before building anything else on top of it.

- [ ] **Step 1: Scaffold the package**

Create `packages/device-sim/package.json`:

```json
{
  "name": "@benefits/device-sim",
  "version": "0.1.0",
  "type": "module",
  "main": "dist/index.js",
  "types": "dist/index.d.ts",
  "scripts": {
    "build": "tsc -p tsconfig.json",
    "test": "vitest run"
  },
  "dependencies": {
    "@benefits/consent-core": "0.1.0",
    "pouchdb-core": "9.0.0",
    "pouchdb-adapter-memory": "9.0.0",
    "pouchdb-adapter-http": "9.0.0",
    "pouchdb-replication": "9.0.0"
  },
  "devDependencies": {
    "@types/pouchdb-core": "7.0.15",
    "@types/pouchdb-adapter-memory": "6.1.7",
    "@types/pouchdb-adapter-http": "6.1.6",
    "@types/pouchdb-replication": "6.4.7"
  }
}
```

Create `packages/device-sim/tsconfig.json`:

```json
{
  "extends": "../../tsconfig.base.json",
  "compilerOptions": { "outDir": "dist", "rootDir": "src" },
  "include": ["src"]
}
```

Run `npm install` from the repo root (the npm workspace picks this package up automatically, matching `packages/audit-verify`'s precedent). If any of the pinned versions above do not actually exist on the npm registry, use the latest 9.x/matching `@types/*` version instead and record the real version you used in your report — these were not independently verified before writing this plan the way EDC's versions were in earlier phases.

- [ ] **Step 2: Write the PouchDB composition**

Create `packages/device-sim/src/pouchdb.ts`:

```typescript
import PouchDB from 'pouchdb-core';
import memoryAdapter from 'pouchdb-adapter-memory';
import httpAdapter from 'pouchdb-adapter-http';
import replicationPlugin from 'pouchdb-replication';

PouchDB.plugin(memoryAdapter);
PouchDB.plugin(httpAdapter);
PouchDB.plugin(replicationPlugin);

// The in-memory adapter is explicit and required (spec section 3, Global
// Constraints) -- this is a fleet of simulated devices in a test
// environment, not real device storage, and pulling in a native
// LevelDB dependency (the default on Node for the batteries-included
// `pouchdb` package) would be pure cost for no benefit here.
export function createLocalDb(name: string): PouchDB.Database {
  return new PouchDB(name, { adapter: 'memory' });
}

export function createRemoteDb(url: string): PouchDB.Database {
  return new PouchDB(url);
}
```

If `PouchDB.plugin` or the constructor signature above does not match what actually compiles against the installed `pouchdb-core` types, adjust to match the real API — this is exactly the kind of thing this gate exists to confirm empirically rather than assume.

- [ ] **Step 3: Write and run the probe**

Create `tools/probe-device-sim-replicate.mjs` (plain Node ESM, not TypeScript, so it runs directly without a build step for a quick probe):

```javascript
import PouchDB from 'pouchdb-core';
import memoryAdapter from 'pouchdb-adapter-memory';
import httpAdapter from 'pouchdb-adapter-http';
import replicationPlugin from 'pouchdb-replication';

PouchDB.plugin(memoryAdapter);
PouchDB.plugin(httpAdapter);
PouchDB.plugin(replicationPlugin);

const local = new PouchDB('probe-local', { adapter: 'memory' });
// If embedding credentials in the URL does not work against the installed
// pouchdb-adapter-http version (Node's native fetch forbids this, per
// consent-couchdb's own integration test comment -- pouchdb-adapter-http
// may or may not use native fetch internally), pass credentials via the
// remote PouchDB constructor's `auth` option instead:
// new PouchDB(url, { auth: { username: 'admin', password: 'benefits' } })
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
```

Create `tools/probe-device-sim-replication.sh`:

```bash
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
```

Run: `chmod +x tools/probe-device-sim-replication.sh && ./tools/probe-device-sim-replication.sh`
Expected: `put into local (memory adapter) OK`, `replicate.to(remote) OK, docs_written=1`, `remote has the document, _rev=...`, `gate 0 probe passed`.

If `consent-couchdb`'s `build-design-doc.js` export name or the design document's `_id` field doesn't match what's used above, read `packages/consent-couchdb/src/build-design-doc.ts` directly and correct the probe to match the real exports.

- [ ] **Step 4: Commit**

```bash
git add packages/device-sim/package.json packages/device-sim/tsconfig.json \
  packages/device-sim/src/pouchdb.ts tools/probe-device-sim-replication.sh \
  tools/probe-device-sim-replicate.mjs package-lock.json
git commit -m "Add device-sim module skeleton and gate 0 PouchDB replication probe"
```

---

### Task 2: Revocation in consent-core

**Files:**
- Modify: `packages/consent-core/src/types.ts`
- Modify: `packages/consent-core/src/evaluate.ts`
- Modify: `packages/consent-core/src/meet.ts`
- Modify: `packages/consent-core/test/evaluate.test.ts`
- Modify: `packages/consent-core/test/meet.test.ts`

**Interfaces:**
- Produces: `ConsentGrant.revokedAt: string | null` (required field). `hasGrant` (unexported, internal to `evaluate.ts`) treats a past `revokedAt` as inactive. `meet()` combines `revokedAt` via the existing `earliest()` helper.

This is a small, additive change to phase 0's already-complete, already-reviewed package. Every change below mirrors the existing `expiresAt` handling exactly, field for field.

- [ ] **Step 1: Write the failing tests**

In `packages/consent-core/test/evaluate.test.ts`, change the `record()` helper's grant literal to include `revokedAt: null`:

```typescript
    consent: {
      participantRef: 'p-001',
      assertedAt: '2026-08-01T00:00:00.000Z',
      grants: [
        {
          field: 'service.satisfaction',
          purpose: 'service-improvement',
          recipientClass: 'statistics',
          grantedAt: '2026-08-01T00:00:00.000Z',
          expiresAt: null,
          revokedAt: null
        }
      ]
    },
```

Then add two tests to the `describe('evaluateRecord boundaries', ...)` block, mirroring the existing expiry boundary tests exactly:

```typescript
  it('treats revocation as exclusive at the exact instant', () => {
    const r = record();
    r.consent.grants[0].revokedAt = '2026-08-30T12:00:00.000Z';
    expect(evaluateRecord(r, '2026-08-30T12:00:00.000Z').allowed).toBe(false);
  });

  it('allows one millisecond before revocation takes effect', () => {
    const r = record();
    r.consent.grants[0].revokedAt = '2026-08-30T12:00:00.001Z';
    expect(evaluateRecord(r, '2026-08-30T12:00:00.000Z').allowed).toBe(true);
  });
```

In `packages/consent-core/test/meet.test.ts`, change the `grant()` helper to accept and default a `revokedAt` parameter:

```typescript
function grant(
  field: string,
  expiresAt: string | null = null,
  grantedAt = '2026-08-01T00:00:00.000Z',
  revokedAt: string | null = null
): ConsentGrant {
  return { field, purpose: 'service-improvement', recipientClass: 'statistics', grantedAt, expiresAt, revokedAt };
}
```

Then add one test to the `describe('meet', ...)` block:

```typescript
  it('takes the earliest revocation', () => {
    const result = meet(
      [grant('a', null, '2026-08-01T00:00:00.000Z', '2026-12-01T00:00:00.000Z')],
      [grant('a', null, '2026-08-01T00:00:00.000Z', '2026-10-01T00:00:00.000Z')]
    );
    expect(result[0].revokedAt).toBe('2026-10-01T00:00:00.000Z');
  });
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd packages/consent-core && npx vitest run test/evaluate.test.ts test/meet.test.ts`
Expected: FAIL — `revokedAt` does not exist on `ConsentGrant` yet, and the new assertions have nothing to check against.

- [ ] **Step 3: Add revokedAt to the type and both functions**

In `packages/consent-core/src/types.ts`, change:

```typescript
export interface ConsentGrant {
  field: FieldId;
  purpose: PurposeId;
  recipientClass: RecipientClass;
  grantedAt: string;
  expiresAt: string | null;
}
```

to:

```typescript
export interface ConsentGrant {
  field: FieldId;
  purpose: PurposeId;
  recipientClass: RecipientClass;
  grantedAt: string;
  expiresAt: string | null;
  revokedAt: string | null;
}
```

In `packages/consent-core/src/evaluate.ts`, in `hasGrant`, add one line immediately after the existing `expiresAt` check:

```typescript
    if (g.expiresAt !== null && g.expiresAt <= nowIso) { continue; }
    if (g.revokedAt !== null && g.revokedAt <= nowIso) { continue; }
```

In `packages/consent-core/src/meet.ts`, in `meet()`, add `revokedAt` to the composed grant using the existing `earliest()` helper (already generic over `string | null`, needs no change):

```typescript
        out.push({
          field: x.field,
          purpose: x.purpose,
          recipientClass: x.recipientClass,
          grantedAt: x.grantedAt > y.grantedAt ? x.grantedAt : y.grantedAt,
          expiresAt: earliest(x.expiresAt, y.expiresAt),
          revokedAt: earliest(x.revokedAt, y.revokedAt)
        });
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd packages/consent-core && npx vitest run test/evaluate.test.ts test/meet.test.ts`
Expected: PASS. This will also break `test/conformance.test.ts` (it reads vector files that don't have `revokedAt` yet, which now fails TypeScript's structural check when the vector JSON is cast to `ConsentGrant`-shaped data) — that's expected and fixed in Task 3, not this one. Confirm the two files above pass in isolation; do not attempt to fix `conformance.test.ts` in this task.

- [ ] **Step 5: Commit**

```bash
git add packages/consent-core/src/types.ts packages/consent-core/src/evaluate.ts \
  packages/consent-core/src/meet.ts packages/consent-core/test/evaluate.test.ts \
  packages/consent-core/test/meet.test.ts
git commit -m "Add revokedAt to ConsentGrant, mirroring expiresAt semantics exactly"
```

---

### Task 3: Update conformance vectors and re-verify against real CouchDB

**Files:**
- Modify: all 15 files under `conformance/vectors/`
- Create: `conformance/vectors/v016-revoked-grant.json`
- Create: `conformance/vectors/v017-revocation-not-yet-effective.json`

**Interfaces:**
- Consumes: `ConsentGrant.revokedAt` (Task 2).
- Produces: nothing new — `packages/consent-core/test/conformance.test.ts` and `packages/consent-couchdb/test/couchdb.integration.test.ts` both already iterate every file in `conformance/vectors/` generically (confirmed by reading both files: neither hard-codes a vector list or count), so neither test file needs any code change. This task's job is purely the vector files themselves.

- [ ] **Step 1: Batch-add `revokedAt: null` to every grant in all 15 existing vectors**

Write a short one-off Node script (do not commit it — this is a one-time migration, not part of the shipped test suite) to do this reliably rather than by hand, since some vectors (check `v009-multi-field-ordering.json` in particular) have more than one grant in their `grants` array and a manual edit risks missing one:

```javascript
import { readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const dir = 'conformance/vectors';
for (const file of readdirSync(dir).filter((f) => f.endsWith('.json'))) {
  const path = join(dir, file);
  const vector = JSON.parse(readFileSync(path, 'utf8'));
  const grants = vector.record?.consent?.grants;
  if (Array.isArray(grants)) {
    for (const g of grants) {
      if (!('revokedAt' in g)) g.revokedAt = null;
    }
  }
  writeFileSync(path, JSON.stringify(vector, null, 2) + '\n');
}
console.log('done');
```

Run it once (`node <script>.js` from the repo root), then delete the script. Diff every changed file (`git diff conformance/vectors/`) and confirm: every grant in every file now has `"revokedAt": null` added, no other field changed, and no vector's `expected` block changed.

- [ ] **Step 2: Add two new vectors for the revoked case**

Create `conformance/vectors/v016-revoked-grant.json`:

```json
{
  "id": "v016-revoked-grant",
  "description": "a grant revoked before the record's evaluation instant is inactive",
  "now": "2026-08-30T12:00:00.000Z",
  "record": {
    "type": "contribution",
    "participantRef": "p-001",
    "siteId": "athens",
    "collectedAt": "2026-08-30T11:00:00.000Z",
    "channel": "app",
    "declaredPurpose": "service-improvement",
    "declaredRecipientClass": "statistics",
    "fields": { "service.satisfaction": 4 },
    "consent": {
      "participantRef": "p-001",
      "assertedAt": "2026-08-01T00:00:00.000Z",
      "grants": [
        {
          "field": "service.satisfaction",
          "purpose": "service-improvement",
          "recipientClass": "statistics",
          "grantedAt": "2026-08-01T00:00:00.000Z",
          "expiresAt": null,
          "revokedAt": "2026-08-15T00:00:00.000Z"
        }
      ]
    }
  },
  "expected": { "allowed": false, "reason": "no-grant:service.satisfaction" }
}
```

Create `conformance/vectors/v017-revocation-not-yet-effective.json`:

```json
{
  "id": "v017-revocation-not-yet-effective",
  "description": "a revocation timestamped after the evaluation instant has not taken effect yet",
  "now": "2026-08-30T12:00:00.000Z",
  "record": {
    "type": "contribution",
    "participantRef": "p-001",
    "siteId": "athens",
    "collectedAt": "2026-08-30T11:00:00.000Z",
    "channel": "app",
    "declaredPurpose": "service-improvement",
    "declaredRecipientClass": "statistics",
    "fields": { "service.satisfaction": 4 },
    "consent": {
      "participantRef": "p-001",
      "assertedAt": "2026-08-01T00:00:00.000Z",
      "grants": [
        {
          "field": "service.satisfaction",
          "purpose": "service-improvement",
          "recipientClass": "statistics",
          "grantedAt": "2026-08-01T00:00:00.000Z",
          "expiresAt": null,
          "revokedAt": "2026-09-01T00:00:00.000Z"
        }
      ]
    }
  },
  "expected": { "allowed": true, "reason": "ok" }
}
```

- [ ] **Step 2: Run consent-core's own conformance suite**

Run: `cd packages/consent-core && npx vitest run test/conformance.test.ts`
Expected: PASS, 17 vectors now (15 existing plus 2 new), all green.

- [ ] **Step 3: Rebuild and re-verify consent-couchdb's design document against real CouchDB**

`build-design-doc.ts` bundles `evaluate.ts`'s actual source at build time, so it picks up Task 2's change automatically — but the built bundle needs regenerating and the integration test needs a real CouchDB to run against.

Run: `ensure_couchdb || docker compose -f deploy/docker-compose.yml up -d couchdb`, then `cd packages/consent-couchdb && npm run build && npx vitest run test/couchdb.integration.test.ts`
Expected: PASS, all 17 vectors (plus the existing tampered-consent test) decided identically by the real CouchDB `validate_doc_update` as by the pure TypeScript function — including the two new revoked-grant vectors, proving the server-side S1 gate enforces revocation exactly like expiry, not just the pure function.

- [ ] **Step 4: Commit**

```bash
git add conformance/vectors/
git commit -m "Add revokedAt to all conformance vectors, add two revocation vectors"
```

---

### Task 4: The Device class — local storage, outbox, revoke action

**Files:**
- Create: `packages/device-sim/src/device.ts`
- Test: `packages/device-sim/test/device.test.ts`

**Interfaces:**
- Consumes: `createLocalDb` (Task 1); `ConsentGrant`, `ConsentAssertion`, `ContributionRecord` (`@benefits/consent-core`, Task 2's updated shape).
- Produces: `class Device { constructor(participantRef: string); contribute(record: Omit<ContributionRecord, '_id'>): Promise<void>; revoke(field: string, purpose: string, recipientClass: string, atIso: string): Promise<void>; unsentRecords(): Promise<ContributionRecord[]>; grants(): ConsentGrant[]; }`. Task 5 (duty cycle) and Task 6 (outbox sweep) both extend this class; Task 7 (replication) reads `unsentRecords()`.

- [ ] **Step 1: Write the failing tests**

Create `packages/device-sim/test/device.test.ts`:

```typescript
import { describe, it, expect, beforeEach } from 'vitest';
import { Device } from '../src/device';

function grant(field: string, purpose = 'service-improvement', recipientClass = 'statistics') {
  return {
    field, purpose, recipientClass,
    grantedAt: '2026-08-01T00:00:00.000Z',
    expiresAt: null,
    revokedAt: null
  };
}

describe('Device', () => {
  let device: Device;

  beforeEach(() => {
    device = new Device('p-001');
  });

  it('starts with no grants and no unsent records', async () => {
    expect(device.grants()).toEqual([]);
    expect(await device.unsentRecords()).toEqual([]);
  });

  it('queues a contributed record as unsent', async () => {
    await device.contribute({
      type: 'contribution', participantRef: 'p-001', siteId: 'athens',
      collectedAt: '2026-08-30T11:00:00.000Z', channel: 'app',
      declaredPurpose: 'service-improvement', declaredRecipientClass: 'statistics',
      fields: { 'service.satisfaction': 4 },
      consent: { participantRef: 'p-001', assertedAt: '2026-08-01T00:00:00.000Z', grants: [grant('service.satisfaction')] }
    });
    const unsent = await device.unsentRecords();
    expect(unsent).toHaveLength(1);
    expect(unsent[0].fields['service.satisfaction']).toBe(4);
  });

  it('revoke() adds a revokedAt-stamped grant that supersedes an existing one via meet semantics', async () => {
    await device.grantConsent(grant('service.satisfaction'));
    await device.revoke('service.satisfaction', 'service-improvement', 'statistics', '2026-08-30T12:00:00.000Z');
    const revoked = device.grants().find((g) => g.field === 'service.satisfaction');
    expect(revoked?.revokedAt).toBe('2026-08-30T12:00:00.000Z');
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd packages/device-sim && npx vitest run test/device.test.ts`
Expected: FAIL — `Device` does not exist yet.

- [ ] **Step 3: Write Device**

Create `packages/device-sim/src/device.ts`:

```typescript
import type { ConsentGrant, ContributionRecord } from '@benefits/consent-core';
import { createLocalDb } from './pouchdb';

// Local grant state lives in a plain array, not PouchDB -- a device's own
// consent grants are small, always fully present locally (never partial),
// and revoke() needs to mutate one in place synchronously from the
// device's point of view. Contributed records go through PouchDB because
// they are what actually needs an offline queue and eventual replication.
export class Device {
  private readonly db: PouchDB.Database;
  private localGrants: ConsentGrant[] = [];

  constructor(private readonly participantRef: string) {
    this.db = createLocalDb(`device-${participantRef}-${Date.now()}-${Math.random().toString(36).slice(2)}`);
  }

  async grantConsent(grant: ConsentGrant): Promise<void> {
    this.localGrants.push(grant);
  }

  grants(): ConsentGrant[] {
    return this.localGrants;
  }

  async revoke(field: string, purpose: string, recipientClass: string, atIso: string): Promise<void> {
    const grant = this.localGrants.find(
      (g) => g.field === field && g.purpose === purpose && g.recipientClass === recipientClass
    );
    if (grant) {
      grant.revokedAt = atIso;
    }
  }

  async contribute(record: Omit<ContributionRecord, '_id'>): Promise<void> {
    await this.db.put({ ...record, _id: `contribution-${Date.now()}-${Math.random().toString(36).slice(2)}` });
  }

  async unsentRecords(): Promise<ContributionRecord[]> {
    const result = await this.db.allDocs({ include_docs: true });
    return result.rows
      .map((row) => row.doc as unknown as ContributionRecord)
      .filter((doc) => doc.type === 'contribution');
  }

  localDb(): PouchDB.Database {
    return this.db;
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd packages/device-sim && npx vitest run test/device.test.ts`
Expected: PASS, all 3 tests.

- [ ] **Step 5: Commit**

```bash
git add packages/device-sim/src/device.ts packages/device-sim/test/device.test.ts
git commit -m "Add Device: local PouchDB storage, outbox, and a revoke action"
```

---

### Task 5: Duty-cycle state machine

**Files:**
- Create: `packages/device-sim/src/dutyCycle.ts`
- Test: `packages/device-sim/test/dutyCycle.test.ts`

**Interfaces:**
- Consumes: nothing from earlier tasks — pure, seedable scheduling logic.
- Produces: `class DutyCycle { constructor(onlineProbability: number, rng?: () => number); isOnline(): boolean; tick(): void; }`. Task 8's end-to-end gate drives many ticks across a fleet using this class.

- [ ] **Step 1: Write the failing tests**

Create `packages/device-sim/test/dutyCycle.test.ts`:

```typescript
import { describe, it, expect } from 'vitest';
import { DutyCycle } from '../src/dutyCycle';

describe('DutyCycle', () => {
  it('is always online when onlineProbability is 1', () => {
    const cycle = new DutyCycle(1, () => 0.999);
    cycle.tick();
    expect(cycle.isOnline()).toBe(true);
  });

  it('is always offline when onlineProbability is 0', () => {
    const cycle = new DutyCycle(0, () => 0.001);
    cycle.tick();
    expect(cycle.isOnline()).toBe(false);
  });

  it('is deterministic given an injected rng', () => {
    const values = [0.9, 0.1, 0.9];
    let i = 0;
    const rng = () => values[i++];
    const cycle = new DutyCycle(0.5, rng);
    cycle.tick();
    expect(cycle.isOnline()).toBe(false); // 0.9 >= 0.5 -> offline
    cycle.tick();
    expect(cycle.isOnline()).toBe(true); // 0.1 < 0.5 -> online
    cycle.tick();
    expect(cycle.isOnline()).toBe(false); // 0.9 >= 0.5 -> offline
  });

  it('rejects a probability outside [0, 1]', () => {
    expect(() => new DutyCycle(1.5)).toThrow();
    expect(() => new DutyCycle(-0.1)).toThrow();
  });
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd packages/device-sim && npx vitest run test/dutyCycle.test.ts`
Expected: FAIL — `DutyCycle` does not exist yet.

- [ ] **Step 3: Write DutyCycle**

Create `packages/device-sim/src/dutyCycle.ts`:

```typescript
// Parameterized rather than hard-coded (spec section 3): phase 7's sweep
// varies onlineProbability against connectivity duty cycles matched to
// the target population without touching this class. The rng is
// injectable so tests (and phase 7's fixed-seed reproducibility
// requirement) are deterministic -- the parent spec requires every
// experiment to run from fixed seeds.
export class DutyCycle {
  private online = false;

  constructor(private readonly onlineProbability: number, private readonly rng: () => number = Math.random) {
    if (onlineProbability < 0 || onlineProbability > 1) {
      throw new Error(`onlineProbability must be in [0, 1], got ${onlineProbability}`);
    }
  }

  tick(): void {
    this.online = this.rng() < this.onlineProbability;
  }

  isOnline(): boolean {
    return this.online;
  }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd packages/device-sim && npx vitest run test/dutyCycle.test.ts`
Expected: PASS, all 4 tests.

- [ ] **Step 5: Commit**

```bash
git add packages/device-sim/src/dutyCycle.ts packages/device-sim/test/dutyCycle.test.ts
git commit -m "Add DutyCycle, a seedable online/offline scheduler"
```

---

### Task 6: The outbox sweep — leak prevention on revoke

**Files:**
- Modify: `packages/device-sim/src/device.ts`
- Modify: `packages/device-sim/test/device.test.ts`

**Interfaces:**
- Consumes: `Device.unsentRecords`, `Device.localDb` (Task 4).
- Produces: `Device.revoke` now also deletes matching unsent records from the local outbox, returning the count deleted: `revoke(...): Promise<{ sweptCount: number }>`. Task 8's end-to-end gate asserts on this to confirm the sweep actually ran, not just that CouchDB ends up clean by coincidence.

This is the mechanism spec section 4 defines as "leak prevention": revocation must sweep the outbox at the moment it happens, not rely on the server to catch a record that was legitimately valid when created. A record's embedded consent is checked field-by-field against the revoked grant's exact `(field, purpose, recipientClass)` triple — the same triple `hasGrant` matches on.

- [ ] **Step 1: Write the failing test**

Add to `packages/device-sim/test/device.test.ts`:

```typescript
  it('revoke sweeps unsent records whose embedded consent covers the revoked grant', async () => {
    await device.grantConsent(grant('service.satisfaction'));
    await device.contribute({
      type: 'contribution', participantRef: 'p-001', siteId: 'athens',
      collectedAt: '2026-08-30T11:00:00.000Z', channel: 'app',
      declaredPurpose: 'service-improvement', declaredRecipientClass: 'statistics',
      fields: { 'service.satisfaction': 4 },
      consent: { participantRef: 'p-001', assertedAt: '2026-08-01T00:00:00.000Z', grants: [grant('service.satisfaction')] }
    });

    const { sweptCount } = await device.revoke('service.satisfaction', 'service-improvement', 'statistics', '2026-08-30T12:00:00.000Z');

    expect(sweptCount).toBe(1);
    expect(await device.unsentRecords()).toEqual([]);
  });

  it('revoke does not sweep a record whose fields do not include the revoked grant', async () => {
    await device.grantConsent(grant('service.satisfaction'));
    await device.grantConsent(grant('service.other'));
    await device.contribute({
      type: 'contribution', participantRef: 'p-001', siteId: 'athens',
      collectedAt: '2026-08-30T11:00:00.000Z', channel: 'app',
      declaredPurpose: 'service-improvement', declaredRecipientClass: 'statistics',
      fields: { 'service.other': 1 },
      consent: { participantRef: 'p-001', assertedAt: '2026-08-01T00:00:00.000Z', grants: [grant('service.other')] }
    });

    const { sweptCount } = await device.revoke('service.satisfaction', 'service-improvement', 'statistics', '2026-08-30T12:00:00.000Z');

    expect(sweptCount).toBe(0);
    expect(await device.unsentRecords()).toHaveLength(1);
  });
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd packages/device-sim && npx vitest run test/device.test.ts`
Expected: FAIL — `revoke` currently returns `void`, not `{ sweptCount }`, and does not touch `unsentRecords`.

- [ ] **Step 3: Implement the sweep**

Replace `revoke` in `packages/device-sim/src/device.ts`:

```typescript
  async revoke(
    field: string, purpose: string, recipientClass: string, atIso: string
  ): Promise<{ sweptCount: number }> {
    const grant = this.localGrants.find(
      (g) => g.field === field && g.purpose === purpose && g.recipientClass === recipientClass
    );
    if (grant) {
      grant.revokedAt = atIso;
    }

    const unsent = await this.unsentRecords();
    let sweptCount = 0;
    for (const record of unsent) {
      if (Object.prototype.hasOwnProperty.call(record.fields, field)
          && record.declaredPurpose === purpose
          && record.declaredRecipientClass === recipientClass) {
        await this.db.remove(record._id!, record._rev!);
        sweptCount++;
      }
    }
    return { sweptCount };
  }
```

`record._rev` needs to be present on what `unsentRecords()` returns — confirm this is true (it comes straight from `allDocs({ include_docs: true })`, whose `doc` field includes `_rev`); if it is not, adjust `unsentRecords()` to preserve it rather than stripping it.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd packages/device-sim && npx vitest run test/device.test.ts`
Expected: PASS, all 6 tests.

- [ ] **Step 5: Commit**

```bash
git add packages/device-sim/src/device.ts packages/device-sim/test/device.test.ts
git commit -m "Sweep unsent outbox records on revoke, closing the leak the phase measures"
```

---

### Task 7: Push replication, conflict injection, and resolution

**Files:**
- Create: `packages/device-sim/src/sync.ts`
- Create: `packages/device-sim/src/conflictInjector.ts`
- Test: `packages/device-sim/test/sync.integration.test.ts`

**Interfaces:**
- Consumes: `createRemoteDb` (Task 1); `Device.localDb`, `Device.unsentRecords` (Task 4/6).
- Produces: `syncDevice(device: Device, remoteUrl: string): Promise<{ pushed: number }>`; `injectConflict(remoteUrl: string, docId: string, conflictingBody: object): Promise<void>`; `resolveConflicts(device: Device, remoteUrl: string): Promise<{ resolvedCount: number }>`. Task 8's end-to-end gate calls all three.

**Read this before writing `resolveConflicts`:** spec section 5's rule is "the device's own most recent local edit wins outright, whole-document" — resolution must track the device's own last-local-edit timestamp independently of PouchDB's revision ordering (which is not guaranteed to agree with wall-clock recency across replicas), not just pick "the first" or "the alphabetically largest" revision, since neither of those is what "user preference" means here.

- [ ] **Step 1: Write the failing integration test**

Create `packages/device-sim/test/sync.integration.test.ts`. This needs a real CouchDB — it is an integration test, following the same `assumeTrue`-style skip-if-unreachable convention `CouchDbAuditStoreIntegrationTest` established in phase 4, adapted to vitest (vitest has no built-in `assumeTrue`; use `it.skipIf`):

```typescript
import { describe, it, expect, beforeAll } from 'vitest';
import { Device } from '../src/device';
import { syncDevice, injectConflict, resolveConflicts } from '../src/sync';

const REMOTE_BASE = 'http://admin:benefits@localhost:5984';
const DB_NAME = 'benefits-device-sim-sync-test';
const REMOTE_URL = `${REMOTE_BASE}/${DB_NAME}`;

let couchDbReachable = false;

beforeAll(async () => {
  try {
    await fetch('http://localhost:5984');
    couchDbReachable = true;
  } catch {
    couchDbReachable = false;
  }
  if (couchDbReachable) {
    await fetch(`http://localhost:5984/${DB_NAME}`, { method: 'DELETE' });
    await fetch(`http://localhost:5984/${DB_NAME}`, {
      method: 'PUT',
      headers: { authorization: 'Basic ' + Buffer.from('admin:benefits').toString('base64') }
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

  it('resolveConflicts keeps the device\'s own local edit over an injected rival revision', async () => {
    const device = new Device('p-sync-2');
    const docId = 'conflict-doc-1';
    await device.localDb().put({
      _id: docId, type: 'contribution', participantRef: 'p-sync-2', siteId: 'athens',
      collectedAt: '2026-08-30T11:00:00.000Z', channel: 'app',
      declaredPurpose: 'service-improvement', declaredRecipientClass: 'statistics',
      fields: { 'service.satisfaction': 4 }, localEditedAt: '2026-08-30T13:00:00.000Z',
      consent: { participantRef: 'p-sync-2', assertedAt: '2026-08-01T00:00:00.000Z', grants: [grant('service.satisfaction')] }
    });

    // Simulate the partition: a rival revision lands on the server, timestamped
    // earlier than the device's own local edit, before the device ever syncs.
    await injectConflict(REMOTE_URL, docId, {
      type: 'contribution', participantRef: 'p-sync-2', siteId: 'athens',
      collectedAt: '2026-08-30T11:00:00.000Z', channel: 'app',
      declaredPurpose: 'service-improvement', declaredRecipientClass: 'statistics',
      fields: { 'service.satisfaction': 999 }, localEditedAt: '2026-08-30T10:00:00.000Z',
      consent: { participantRef: 'p-sync-2', assertedAt: '2026-08-01T00:00:00.000Z', grants: [grant('service.satisfaction')] }
    });

    await syncDevice(device, REMOTE_URL);
    const resolved = await resolveConflicts(device, REMOTE_URL);
    expect(resolved.resolvedCount).toBe(1);

    const remoteDb = new (await import('../src/pouchdb')).createRemoteDb(REMOTE_URL);
    const finalDoc = await remoteDb.get(docId) as unknown as { fields: { 'service.satisfaction': number }; _conflicts?: string[] };
    expect(finalDoc.fields['service.satisfaction']).toBe(4);

    const withConflicts = await remoteDb.get(docId, { conflicts: true }) as unknown as { _conflicts?: string[] };
    expect(withConflicts._conflicts ?? []).toEqual([]);
  });
});
```

If constructing `createRemoteDb` via a dynamic `import()` inside the test above is awkward, import it normally at the top of the file instead — it was written this way only to keep the diff minimal against the interface list; use whichever form is idiomatic.

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd packages/device-sim && npx vitest run test/sync.integration.test.ts`
Expected: FAIL (or SKIPPED if CouchDB is not reachable — start it first: `ensure_couchdb` from `tools/lib.sh`, or `docker compose -f deploy/docker-compose.yml up -d couchdb`; do not accept a skip as this task's final state) — `syncDevice`/`injectConflict`/`resolveConflicts` do not exist yet.

- [ ] **Step 3: Write sync.ts and conflictInjector.ts**

Create `packages/device-sim/src/sync.ts`:

```typescript
import { createRemoteDb } from './pouchdb';
import { Device } from './device';

export async function syncDevice(device: Device, remoteUrl: string): Promise<{ pushed: number }> {
  const remote = createRemoteDb(remoteUrl);
  const result = await new Promise<{ docs_written: number }>((resolve, reject) => {
    device.localDb().replicate.to(remote).on('complete', resolve).on('error', reject);
  });
  return { pushed: result.docs_written };
}

// "User preference first" (spec section 5): the device's own most recent
// local edit wins outright, tracked via each document's own
// `localEditedAt` field rather than trusted PouchDB revision ordering,
// which reflects replication history, not wall-clock recency across
// replicas.
export async function resolveConflicts(device: Device, remoteUrl: string): Promise<{ resolvedCount: number }> {
  const remote = createRemoteDb(remoteUrl);
  const allDocs = await remote.allDocs({ include_docs: true, conflicts: true });
  let resolvedCount = 0;

  for (const row of allDocs.rows) {
    const doc = row.doc as unknown as { _id: string; _rev: string; _conflicts?: string[]; localEditedAt?: string };
    if (!doc._conflicts || doc._conflicts.length === 0) {
      continue;
    }

    const rivals = await Promise.all(
      doc._conflicts.map((rev) => remote.get(doc._id, { rev }) as unknown as Promise<{ _rev: string; localEditedAt?: string }>)
    );
    const all = [{ _rev: doc._rev, localEditedAt: doc.localEditedAt }, ...rivals];
    all.sort((a, b) => (b.localEditedAt ?? '').localeCompare(a.localEditedAt ?? ''));
    const winner = all[0];
    const losers = all.slice(1);

    if (winner._rev !== doc._rev) {
      // The winning revision was one of the rivals, not the one allDocs
      // returned as current -- fetch its full body so the local device's
      // own edit still ends up as the live document content.
      await remote.put({ ...(await remote.get(doc._id, { rev: winner._rev })), _id: doc._id });
    }
    for (const loser of losers) {
      await remote.remove(doc._id, loser._rev);
    }
    resolvedCount++;
  }

  return { resolvedCount };
}
```

Create `packages/device-sim/src/conflictInjector.ts`:

```typescript
// Deliberately bypasses Device/PouchDB entirely: a direct HTTP write to
// CouchDB, simulating "something else touched this document while the
// device was offline and unaware" without needing to simulate a second
// device (spec section 5's rejected alternative).
export async function injectConflict(remoteUrl: string, docId: string, conflictingBody: object): Promise<void> {
  const url = new URL(remoteUrl);
  const auth = url.username && url.password
    ? 'Basic ' + Buffer.from(`${decodeURIComponent(url.username)}:${decodeURIComponent(url.password)}`).toString('base64')
    : undefined;
  url.username = '';
  url.password = '';

  const existing = await fetch(`${url.toString()}/${docId}`, {
    headers: auth ? { authorization: auth } : {}
  });
  const rev = existing.ok ? (await existing.json())._rev : undefined;

  const res = await fetch(`${url.toString()}/${docId}${rev ? `?new_edits=false` : ''}`, {
    method: 'PUT',
    headers: { 'content-type': 'application/json', ...(auth ? { authorization: auth } : {}) },
    body: JSON.stringify({ ...conflictingBody, _id: docId, _rev: rev })
  });
  if (!res.ok && res.status !== 409) {
    throw new Error(`injectConflict failed: ${res.status} ${await res.text()}`);
  }
}
```

The `new_edits=false` / conflicting-revision-tree mechanics above are the part most likely to need correction against real CouchDB behavior — CouchDB requires a document's new revision to be a valid descendant of an existing `_rev` for a normal PUT, so producing a genuine sibling conflict (not just a normal update, and not a rejected write) may need `?new_edits=false` with an explicit `_revisions` history object rather than a bare `_rev`, or two independent PouchDB-side `put`s to two different local databases that are then both replicated to the same remote without either having seen the other first. If the test in Step 1 does not actually produce a non-empty `_conflicts` array on the remote document after `syncDevice` runs, this is gate 3's own uncertainty (spec section 8, open question 2) — investigate CouchDB's real conflict-creation mechanics directly (its documentation, or by experimenting against the running `deploy/docker-compose.yml` instance) rather than guessing further, and correct this file to match what actually produces a real conflict.

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd packages/device-sim && npx vitest run test/sync.integration.test.ts`
Expected: PASS, both tests, against a real CouchDB (confirm this in your report — not skipped).

- [ ] **Step 5: Commit**

```bash
git add packages/device-sim/src/sync.ts packages/device-sim/src/conflictInjector.ts \
  packages/device-sim/test/sync.integration.test.ts
git commit -m "Add push replication, conflict injection, and user-preference-first resolution"
```

---

### Task 8: End-to-end gate

**Files:**
- Create: `packages/device-sim/test/fleet.integration.test.ts`
- Create: `tools/probe-device-fleet-e2e.sh`

**Interfaces:**
- Consumes: everything from Tasks 1-7.
- Produces: the phase's Gate 4 result — a fleet of simulated devices, a real duty cycle, some revoking mid-partition and some hitting an injected conflict, ending with zero leaked records and zero unresolved conflicts, verified against real CouchDB.

- [ ] **Step 1: Write the end-to-end fleet test**

Create `packages/device-sim/test/fleet.integration.test.ts`:

```typescript
import { describe, it, expect, beforeAll } from 'vitest';
import { Device } from '../src/device';
import { DutyCycle } from '../src/dutyCycle';
import { syncDevice, injectConflict, resolveConflicts } from '../src/sync';
import { createRemoteDb } from '../src/pouchdb';

const DB_NAME = 'benefits-device-sim-fleet-test';
const REMOTE_URL = `http://admin:benefits@localhost:5984/${DB_NAME}`;

let couchDbReachable = false;

beforeAll(async () => {
  try {
    await fetch('http://localhost:5984');
    couchDbReachable = true;
  } catch {
    couchDbReachable = false;
  }
  if (couchDbReachable) {
    await fetch(`http://localhost:5984/${DB_NAME}`, { method: 'DELETE' });
    await fetch(`http://localhost:5984/${DB_NAME}`, {
      method: 'PUT',
      headers: { authorization: 'Basic ' + Buffer.from('admin:benefits').toString('base64') }
    });
  }
}, 30000);

function grant(field: string) {
  return {
    field, purpose: 'service-improvement', recipientClass: 'statistics',
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
    await devices[0].contribute({
      type: 'contribution', participantRef: 'p-fleet-0', siteId: 'athens',
      collectedAt: '2026-08-30T11:00:00.000Z', channel: 'app',
      declaredPurpose: 'service-improvement', declaredRecipientClass: 'statistics',
      fields: { 'service.satisfaction': 4 },
      consent: { participantRef: 'p-fleet-0', assertedAt: '2026-08-01T00:00:00.000Z', grants: [grant('service.satisfaction')] }
    });
    const revokeResult = await devices[0].revoke('service.satisfaction', 'service-improvement', 'statistics', '2026-08-30T12:00:00.000Z');
    expect(revokeResult.sweptCount).toBe(1);

    // Device 1: an injected conflict, exactly as Task 7 proved works.
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
      fields: { 'service.satisfaction': 1 }, localEditedAt: '2026-08-30T09:00:00.000Z',
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

    // Zero leaked records: device 0's revoked-and-swept record must not
    // appear anywhere in CouchDB.
    const leaked = allRemoteDocs.rows.filter(
      (row) => (row.doc as unknown as { participantRef?: string })?.participantRef === 'p-fleet-0'
    );
    expect(leaked).toHaveLength(0);

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
```

- [ ] **Step 2: Run the test**

Run: `cd packages/device-sim && npx vitest run test/fleet.integration.test.ts`
Expected: PASS, against a real CouchDB (start it first if needed). If the leaked-records assertion fails, that is Gate 4 failing for real — do not adjust the assertion to make it pass; find and fix the actual defect in the sweep or sync path.

- [ ] **Step 3: Write the shell probe wrapper**

Create `tools/probe-device-fleet-e2e.sh`:

```bash
#!/usr/bin/env bash
# Gate 4: the full device-sim fleet test, run standalone the same way every
# other phase's end-to-end gate is run from tools/.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

ensure_couchdb
cd "$ROOT/packages/device-sim"
npx vitest run test/fleet.integration.test.ts
```

Run: `chmod +x tools/probe-device-fleet-e2e.sh && ./tools/probe-device-fleet-e2e.sh`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add packages/device-sim/test/fleet.integration.test.ts tools/probe-device-fleet-e2e.sh
git commit -m "Add phase 5 end-to-end gate: fleet revocation and conflict resolution"
```

---

## After all tasks

Run the full suite once more end to end before declaring the phase done:

```bash
npm run test --workspaces --if-present
./tools/probe-device-sim-replication.sh
./tools/probe-device-fleet-e2e.sh
```

All three must be green. Then proceed to the whole-branch review per superpowers:subagent-driven-development.
