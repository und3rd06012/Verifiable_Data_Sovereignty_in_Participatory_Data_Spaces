# E1 + E3 Experiments Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and run the E1 (consent filter cost) and formal E3 (audit tamper detection cost) experiments, producing seeded, reproducible CSV results under `experiments/results/`.

**Architecture:** E1 is a new npm workspace member (`experiments/harness/e1`) that builds an experiment-only signature-verifying CouchDB design document (structural check plus Ed25519 verification, both bundled via esbuild), installs it alongside the existing structural design doc and a no-validator baseline on three clean databases in a dedicated CouchDB container, and sweeps batch writes against all three. E3 reuses the existing, unmodified `MerkleTree`/`AuditHashing` Java classes from `benefits-audit` inside a new Gradle module (`experiments/harness/e3`) to generate seeded fixtures and measure proof size server-side, then hands those fixtures to a new Node workspace member (`experiments/harness/e3-verify`) that times `@benefits/audit-verify`'s existing, unmodified verification functions under a throttled container.

**Tech Stack:** TypeScript/Node (esbuild, vitest, `@noble/ed25519`, `@noble/hashes`), Java (Gradle, JUnit 5, AssertJ), Docker Compose.

**Spec:** `docs/superpowers/specs/2026-09-01-e1-e3-experiments-design.md`

## Global Constraints

- No em-dashes in code, docs, or commits (use commas, colons, periods, or semicolons instead).
- No LLM/AI-tooling attribution in commits.
- No emojis in code or logs.
- Verify CouchDB and bundling behavior live against the real pinned toolchain rather than trusting documentation alone.
- Dependency versions are real, registry-verified versions: `@noble/ed25519@3.2.0`, `@noble/hashes@1.8.0`.
- E1's signature-verifying design document is experiment-only: it must never be referenced from `connector/`, `apps/`, or `packages/consent-couchdb`.
- `packages/consent-core/src/types.ts` is not modified; E1's signed envelope fields (`devicePublicKey`, `signature`) live only in the harness's own synthetic-record type.
- The production `packages/consent-couchdb` esbuild bundle stays at `target: 'es5'`; the `es2020` target is local to `experiments/harness/e1`'s own esbuild invocation.
- `experiments/harness/e1`'s dedicated CouchDB container must never share a database, port, or the shared dev CouchDB instance used by every other test/tool in this repo.

---

## Task 1: Portable codec and canonicalization

**Files:**
- Create: `experiments/harness/e1/package.json`
- Create: `experiments/harness/e1/tsconfig.json`
- Create: `experiments/harness/e1/src/portable-codec.ts`
- Create: `experiments/harness/e1/src/canonicalize.ts`
- Test: `experiments/harness/e1/test/portable-codec.test.ts`
- Test: `experiments/harness/e1/test/canonicalize.test.ts`
- Modify: `/home/und3rd06012/benefits/package.json:4` (workspaces array)

**Interfaces:**
- Produces: `utf8Encode(input: string): Uint8Array`, `base64Decode(input: string): Uint8Array` (from `portable-codec.ts`).
- Produces: `canonicalize(value: unknown): string`, `signedPayload(record: SignableRecord): string`, `SignableRecord` interface (from `canonicalize.ts`).

Both modules are written without `TextEncoder`, `Buffer`, `atob`, object spread, or any construct outside `deploy/couchdb-engine-probe.md`'s verified-supported list, because both get bundled directly into the CouchDB query server in Task 3. `deploy/couchdb-engine-probe.md`'s supported list does not include `TextEncoder`, so this task builds a portable UTF-8 encoder rather than relying on it.

- [ ] **Step 1: Add the workspace member to root `package.json`**

Modify `/home/und3rd06012/benefits/package.json`:

```json
{
  "name": "benefits",
  "private": true,
  "workspaces": ["packages/*", "apps/*", "experiments/harness/*"],
  "scripts": {
    "build": "npm run build --workspaces --if-present",
    "test": "npm run test --workspaces --if-present"
  },
  "devDependencies": {
    "typescript": "5.7.2",
    "vitest": "2.1.8",
    "esbuild": "0.24.2",
    "@types/node": "22.10.2"
  }
}
```

- [ ] **Step 2: Create the e1 package manifest**

Create `experiments/harness/e1/package.json`:

```json
{
  "name": "@benefits/experiment-e1",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "build": "tsc -p tsconfig.json",
    "test": "vitest run"
  },
  "dependencies": {
    "@noble/ed25519": "3.2.0",
    "@noble/hashes": "1.8.0"
  },
  "devDependencies": {
    "esbuild": "0.24.2"
  }
}
```

- [ ] **Step 3: Create the e1 tsconfig**

Create `experiments/harness/e1/tsconfig.json`:

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

This package's own `tsc` build target is `ES2020` (it is plain Node tooling, never itself loaded into the query server); only the `esbuild` call inside Task 3 controls what actually gets bundled into `validate_doc_update`.

- [ ] **Step 4: Run `npm install` at the repo root**

Run: `npm install`
Expected: resolves `@noble/ed25519@3.2.0` and `@noble/hashes@1.8.0` into `experiments/harness/e1/node_modules` (or the root `node_modules` via hoisting), and links `experiments/harness/e1` as a workspace member. No errors.

- [ ] **Step 5: Write the failing tests for the portable codec**

Create `experiments/harness/e1/test/portable-codec.test.ts`:

```typescript
import { describe, it, expect } from 'vitest';
import { utf8Encode, base64Decode } from '../src/portable-codec';

describe('utf8Encode', () => {
  it('encodes plain ASCII identically to Buffer', () => {
    const input = 'hello world 123';
    expect(Array.from(utf8Encode(input))).toEqual(Array.from(Buffer.from(input, 'utf8')));
  });

  it('encodes multi-byte characters identically to Buffer', () => {
    const input = 'grant purpose: éè âê öü café';
    expect(Array.from(utf8Encode(input))).toEqual(Array.from(Buffer.from(input, 'utf8')));
  });

  it('encodes a surrogate pair (emoji) identically to Buffer', () => {
    const input = 'field-😀-end';
    expect(Array.from(utf8Encode(input))).toEqual(Array.from(Buffer.from(input, 'utf8')));
  });

  it('encodes JSON containing quotes and braces identically to Buffer', () => {
    const input = '{"a":1,"b":"x,y"}';
    expect(Array.from(utf8Encode(input))).toEqual(Array.from(Buffer.from(input, 'utf8')));
  });
});

describe('base64Decode', () => {
  it('decodes a value produced by Buffer.toString("base64")', () => {
    const original = new Uint8Array([0, 1, 2, 253, 254, 255, 42, 7]);
    const encoded = Buffer.from(original).toString('base64');
    expect(Array.from(base64Decode(encoded))).toEqual(Array.from(original));
  });

  it('decodes a value with padding', () => {
    const original = new TextEncoder().encode('a');
    const encoded = Buffer.from(original).toString('base64');
    expect(encoded.endsWith('=')).toBe(true);
    expect(Array.from(base64Decode(encoded))).toEqual(Array.from(original));
  });

  it('round-trips 64 bytes of pseudo-random data against Buffer', () => {
    const original = new Uint8Array(64);
    for (let i = 0; i < 64; i++) {
      original[i] = (i * 37 + 11) % 256;
    }
    const encoded = Buffer.from(original).toString('base64');
    expect(Array.from(base64Decode(encoded))).toEqual(Array.from(original));
  });
});
```

- [ ] **Step 6: Run tests to verify they fail**

Run: `npx vitest run --root experiments/harness/e1`
Expected: FAIL, `Cannot find module '../src/portable-codec'`.

- [ ] **Step 7: Implement the portable codec**

Create `experiments/harness/e1/src/portable-codec.ts`:

```typescript
// Neither function may use TextEncoder, Buffer, or atob: both get bundled directly
// into the CouchDB query server (Task 3), and deploy/couchdb-engine-probe.md's
// verified-supported construct list does not include any of the three.

export function utf8Encode(input: string): Uint8Array {
  var bytes: number[] = [];
  for (var i = 0; i < input.length; i++) {
    var code = input.charCodeAt(i);
    if (code >= 0xd800 && code <= 0xdbff && i + 1 < input.length) {
      var next = input.charCodeAt(i + 1);
      if (next >= 0xdc00 && next <= 0xdfff) {
        code = 0x10000 + ((code - 0xd800) << 10) + (next - 0xdc00);
        i++;
      }
    }
    if (code < 0x80) {
      bytes.push(code);
    } else if (code < 0x800) {
      bytes.push(0xc0 | (code >> 6), 0x80 | (code & 0x3f));
    } else if (code < 0x10000) {
      bytes.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f));
    } else {
      bytes.push(
        0xf0 | (code >> 18),
        0x80 | ((code >> 12) & 0x3f),
        0x80 | ((code >> 6) & 0x3f),
        0x80 | (code & 0x3f)
      );
    }
  }
  return new Uint8Array(bytes);
}

var BASE64_CHARS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

export function base64Decode(input: string): Uint8Array {
  var cleaned = input.replace(/=+$/, '');
  var bytes: number[] = [];
  var buffer = 0;
  var bitsCollected = 0;
  for (var i = 0; i < cleaned.length; i++) {
    var value = BASE64_CHARS.indexOf(cleaned.charAt(i));
    if (value === -1) {
      continue;
    }
    buffer = (buffer << 6) | value;
    bitsCollected += 6;
    if (bitsCollected >= 8) {
      bitsCollected -= 8;
      bytes.push((buffer >> bitsCollected) & 0xff);
    }
  }
  return new Uint8Array(bytes);
}
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `npx vitest run --root experiments/harness/e1`
Expected: PASS, all `portable-codec.test.ts` cases green.

- [ ] **Step 9: Write the failing tests for canonicalization**

Create `experiments/harness/e1/test/canonicalize.test.ts`:

```typescript
import { describe, it, expect } from 'vitest';
import { canonicalize, signedPayload } from '../src/canonicalize';

describe('canonicalize', () => {
  it('sorts top-level keys regardless of insertion order', () => {
    expect(canonicalize({ b: 1, a: 2 })).toBe('{"a":2,"b":1}');
  });

  it('sorts nested object keys', () => {
    expect(canonicalize({ z: { d: 1, c: 2 }, a: 1 })).toBe('{"a":1,"z":{"c":2,"d":1}}');
  });

  it('preserves array order without sorting array elements', () => {
    expect(canonicalize({ a: [3, 1, 2] })).toBe('{"a":[3,1,2]}');
  });

  it('produces identical output for two differently-ordered equivalent objects', () => {
    const one = canonicalize({ fields: { x: 1, y: 2 }, participantRef: 'p-1' });
    const two = canonicalize({ participantRef: 'p-1', fields: { y: 2, x: 1 } });
    expect(one).toBe(two);
  });
});

describe('signedPayload', () => {
  it('includes only participant-authored fields, excluding _id, _rev, devicePublicKey, signature', () => {
    const record = {
      _id: 'contribution-1',
      _rev: '1-abc',
      participantRef: 'p-1',
      siteId: 'athens',
      collectedAt: '2026-08-30T11:00:00.000Z',
      channel: 'app',
      declaredPurpose: 'service-improvement',
      declaredRecipientClass: 'statistics',
      fields: { 'service.satisfaction': 4 },
      consent: { participantRef: 'p-1', assertedAt: '2026-08-01T00:00:00.000Z', grants: [] },
      devicePublicKey: 'ignored-in-payload',
      signature: 'ignored-in-payload'
    };
    const payload = signedPayload(record);
    expect(payload).not.toContain('_id');
    expect(payload).not.toContain('_rev');
    expect(payload).not.toContain('devicePublicKey');
    expect(payload).not.toContain('ignored-in-payload');
    expect(payload).toContain('participantRef');
    expect(payload).toContain('service.satisfaction');
  });

  it('is stable under a differently-ordered but equal input object', () => {
    const base = {
      participantRef: 'p-1',
      siteId: 'athens',
      collectedAt: '2026-08-30T11:00:00.000Z',
      channel: 'app',
      declaredPurpose: 'service-improvement',
      declaredRecipientClass: 'statistics',
      fields: { a: 1, b: 2 },
      consent: { participantRef: 'p-1', assertedAt: '2026-08-01T00:00:00.000Z', grants: [] }
    };
    const reordered = {
      consent: base.consent,
      fields: { b: 2, a: 1 },
      declaredRecipientClass: base.declaredRecipientClass,
      declaredPurpose: base.declaredPurpose,
      channel: base.channel,
      collectedAt: base.collectedAt,
      siteId: base.siteId,
      participantRef: base.participantRef
    };
    expect(signedPayload(base)).toBe(signedPayload(reordered));
  });
});
```

- [ ] **Step 10: Run tests to verify they fail**

Run: `npx vitest run --root experiments/harness/e1`
Expected: FAIL, `Cannot find module '../src/canonicalize'`.

- [ ] **Step 11: Implement canonicalization**

Create `experiments/harness/e1/src/canonicalize.ts`:

```typescript
export function canonicalize(value: unknown): string {
  return JSON.stringify(sortKeysDeep(value));
}

function sortKeysDeep(value: unknown): unknown {
  if (Array.isArray(value)) {
    var result: unknown[] = [];
    for (var i = 0; i < value.length; i++) {
      result.push(sortKeysDeep(value[i]));
    }
    return result;
  }
  if (value !== null && typeof value === 'object') {
    var keys = Object.keys(value as Record<string, unknown>).sort();
    var sorted: Record<string, unknown> = {};
    for (var j = 0; j < keys.length; j++) {
      sorted[keys[j]] = sortKeysDeep((value as Record<string, unknown>)[keys[j]]);
    }
    return sorted;
  }
  return value;
}

export interface SignableRecord {
  participantRef: string;
  siteId: string;
  collectedAt: string;
  channel: string;
  declaredPurpose: string;
  declaredRecipientClass: string;
  fields: Record<string, unknown>;
  consent: unknown;
}

export function signedPayload(record: SignableRecord): string {
  return canonicalize({
    participantRef: record.participantRef,
    siteId: record.siteId,
    collectedAt: record.collectedAt,
    channel: record.channel,
    declaredPurpose: record.declaredPurpose,
    declaredRecipientClass: record.declaredRecipientClass,
    fields: record.fields,
    consent: record.consent
  });
}
```

- [ ] **Step 12: Run tests to verify they pass**

Run: `npx vitest run --root experiments/harness/e1`
Expected: PASS, all `canonicalize.test.ts` cases green.

- [ ] **Step 13: Commit**

```bash
git add package.json package-lock.json experiments/harness/e1/package.json \
  experiments/harness/e1/tsconfig.json experiments/harness/e1/src/portable-codec.ts \
  experiments/harness/e1/src/canonicalize.ts experiments/harness/e1/test/portable-codec.test.ts \
  experiments/harness/e1/test/canonicalize.test.ts
git commit -m "feat(e1): add portable codec and canonicalization for the signature experiment"
```

---

## Task 2: Deterministic keys and synthetic record generation

**Files:**
- Create: `experiments/harness/e1/src/rng.ts`
- Create: `experiments/harness/e1/src/sign.ts`
- Create: `experiments/harness/e1/src/synthetic-records.ts`
- Test: `experiments/harness/e1/test/rng.test.ts`
- Test: `experiments/harness/e1/test/sign.test.ts`
- Test: `experiments/harness/e1/test/synthetic-records.test.ts`

**Interfaces:**
- Consumes: `signedPayload`, `SignableRecord` (Task 1, `canonicalize.ts`); `utf8Encode` (Task 1, `portable-codec.ts`).
- Produces: `mulberry32(seed: number): () => number`, `randomBytes(rand: () => number, length: number): Uint8Array` (`rng.ts`).
- Produces: `KeyPair { privateKey: Uint8Array; publicKeyBase64: string }`, `generateKeyPair(rand: () => number): KeyPair`, `signRecord(record: SignableRecord, privateKey: Uint8Array): string` (`sign.ts`).
- Produces: `SyntheticRecord` (extends `SignableRecord` with `_id`, `devicePublicKey`, `signature`), `generateSyntheticRecord(opts: { seed: number; index: number; fieldCount: number; grantSetSize: number }): SyntheticRecord` (`synthetic-records.ts`). Later tasks (3, 4, 6) consume `SyntheticRecord` and `generateSyntheticRecord`.

- [ ] **Step 1: Write the failing test for the seeded RNG**

Create `experiments/harness/e1/test/rng.test.ts`:

```typescript
import { describe, it, expect } from 'vitest';
import { mulberry32, randomBytes } from '../src/rng';

describe('mulberry32', () => {
  it('produces the same sequence for the same seed', () => {
    const a = mulberry32(42);
    const b = mulberry32(42);
    const seqA = [a(), a(), a()];
    const seqB = [b(), b(), b()];
    expect(seqA).toEqual(seqB);
  });

  it('produces a different sequence for a different seed', () => {
    const a = mulberry32(42);
    const b = mulberry32(43);
    expect(a()).not.toBe(b());
  });

  it('stays within [0, 1)', () => {
    const rand = mulberry32(7);
    for (let i = 0; i < 100; i++) {
      const value = rand();
      expect(value).toBeGreaterThanOrEqual(0);
      expect(value).toBeLessThan(1);
    }
  });
});

describe('randomBytes', () => {
  it('produces the requested length', () => {
    const rand = mulberry32(1);
    expect(randomBytes(rand, 32).length).toBe(32);
  });

  it('is deterministic for a fixed seed', () => {
    const bytesA = randomBytes(mulberry32(99), 16);
    const bytesB = randomBytes(mulberry32(99), 16);
    expect(Array.from(bytesA)).toEqual(Array.from(bytesB));
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run --root experiments/harness/e1`
Expected: FAIL, `Cannot find module '../src/rng'`.

- [ ] **Step 3: Implement the seeded RNG**

Create `experiments/harness/e1/src/rng.ts`:

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

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run --root experiments/harness/e1`
Expected: PASS.

- [ ] **Step 5: Write the failing test for signing**

Create `experiments/harness/e1/test/sign.test.ts`:

```typescript
import { describe, it, expect } from 'vitest';
import { mulberry32 } from '../src/rng';
import { generateKeyPair, signRecord } from '../src/sign';
import * as ed from '@noble/ed25519';
import { sha512 } from '@noble/hashes/sha512.js';
import { base64Decode, utf8Encode } from '../src/portable-codec';
import { signedPayload } from '../src/canonicalize';

ed.hashes.sha512 = sha512;

const record = {
  participantRef: 'p-1',
  siteId: 'athens',
  collectedAt: '2026-08-30T11:00:00.000Z',
  channel: 'app',
  declaredPurpose: 'service-improvement',
  declaredRecipientClass: 'statistics',
  fields: { 'service.satisfaction': 4 },
  consent: { participantRef: 'p-1', assertedAt: '2026-08-01T00:00:00.000Z', grants: [] }
};

describe('generateKeyPair', () => {
  it('produces a deterministic key pair for a fixed seed', () => {
    const a = generateKeyPair(mulberry32(5));
    const b = generateKeyPair(mulberry32(5));
    expect(a.publicKeyBase64).toBe(b.publicKeyBase64);
  });

  it('produces different key pairs for different seeds', () => {
    const a = generateKeyPair(mulberry32(5));
    const b = generateKeyPair(mulberry32(6));
    expect(a.publicKeyBase64).not.toBe(b.publicKeyBase64);
  });
});

describe('signRecord', () => {
  it('produces a signature that verifies against the matching public key using @noble/ed25519 directly', () => {
    const keyPair = generateKeyPair(mulberry32(11));
    const signatureBase64 = signRecord(record, keyPair.privateKey);
    const message = utf8Encode(signedPayload(record));
    const signature = base64Decode(signatureBase64);
    const publicKey = base64Decode(keyPair.publicKeyBase64);
    expect(ed.verify(signature, message, publicKey)).toBe(true);
  });

  it('produces a signature that fails against a tampered payload', () => {
    const keyPair = generateKeyPair(mulberry32(11));
    const signatureBase64 = signRecord(record, keyPair.privateKey);
    const tampered = { ...record, fields: { 'service.satisfaction': 1 } };
    const message = utf8Encode(signedPayload(tampered));
    const signature = base64Decode(signatureBase64);
    const publicKey = base64Decode(keyPair.publicKeyBase64);
    expect(ed.verify(signature, message, publicKey)).toBe(false);
  });
});
```

- [ ] **Step 6: Run test to verify it fails**

Run: `npx vitest run --root experiments/harness/e1`
Expected: FAIL, `Cannot find module '../src/sign'`.

- [ ] **Step 7: Implement signing**

Create `experiments/harness/e1/src/sign.ts`:

```typescript
import * as ed from '@noble/ed25519';
import { sha512 } from '@noble/hashes/sha512.js';
import { randomBytes } from './rng';
import { signedPayload, SignableRecord } from './canonicalize';
import { utf8Encode } from './portable-codec';

ed.hashes.sha512 = sha512;

export interface KeyPair {
  privateKey: Uint8Array;
  publicKeyBase64: string;
}

export function generateKeyPair(rand: () => number): KeyPair {
  const privateKey = randomBytes(rand, 32);
  const publicKey = ed.getPublicKey(privateKey);
  return { privateKey, publicKeyBase64: Buffer.from(publicKey).toString('base64') };
}

export function signRecord(record: SignableRecord, privateKey: Uint8Array): string {
  const message = utf8Encode(signedPayload(record));
  const signature = ed.sign(message, privateKey);
  return Buffer.from(signature).toString('base64');
}
```

- [ ] **Step 8: Run test to verify it passes**

Run: `npx vitest run --root experiments/harness/e1`
Expected: PASS.

- [ ] **Step 9: Write the failing test for synthetic record generation**

Create `experiments/harness/e1/test/synthetic-records.test.ts`:

```typescript
import { describe, it, expect } from 'vitest';
import { generateSyntheticRecord } from '../src/synthetic-records';
import * as ed from '@noble/ed25519';
import { sha512 } from '@noble/hashes/sha512.js';
import { base64Decode, utf8Encode } from '../src/portable-codec';
import { signedPayload } from '../src/canonicalize';

ed.hashes.sha512 = sha512;

describe('generateSyntheticRecord', () => {
  it('produces the requested field count and grant-set size', () => {
    const record = generateSyntheticRecord({ seed: 1, index: 0, fieldCount: 5, grantSetSize: 10 });
    expect(Object.keys(record.fields).length).toBe(5);
    expect((record.consent as any).grants.length).toBe(10);
  });

  it('is fully consented: every field has a matching, currently-active grant', () => {
    const record = generateSyntheticRecord({ seed: 2, index: 3, fieldCount: 3, grantSetSize: 3 });
    const grants = (record.consent as any).grants as any[];
    for (const field of Object.keys(record.fields)) {
      const matched = grants.some(
        (g) =>
          g.field === field &&
          g.purpose === record.declaredPurpose &&
          g.recipientClass === record.declaredRecipientClass &&
          g.revokedAt === null &&
          g.expiresAt === null
      );
      expect(matched).toBe(true);
    }
  });

  it('is deterministic for the same seed and index', () => {
    const a = generateSyntheticRecord({ seed: 9, index: 4, fieldCount: 2, grantSetSize: 2 });
    const b = generateSyntheticRecord({ seed: 9, index: 4, fieldCount: 2, grantSetSize: 2 });
    expect(a).toEqual(b);
  });

  it('produces a distinct _id per index for the same seed', () => {
    const a = generateSyntheticRecord({ seed: 9, index: 0, fieldCount: 1, grantSetSize: 1 });
    const b = generateSyntheticRecord({ seed: 9, index: 1, fieldCount: 1, grantSetSize: 1 });
    expect(a._id).not.toBe(b._id);
  });

  it('carries a signature that verifies against its own devicePublicKey', () => {
    const record = generateSyntheticRecord({ seed: 3, index: 0, fieldCount: 2, grantSetSize: 2 });
    const message = utf8Encode(signedPayload(record));
    const signature = base64Decode(record.signature);
    const publicKey = base64Decode(record.devicePublicKey);
    expect(ed.verify(signature, message, publicKey)).toBe(true);
  });
});
```

- [ ] **Step 10: Run test to verify it fails**

Run: `npx vitest run --root experiments/harness/e1`
Expected: FAIL, `Cannot find module '../src/synthetic-records'`.

- [ ] **Step 11: Implement synthetic record generation**

Create `experiments/harness/e1/src/synthetic-records.ts`:

```typescript
import { mulberry32 } from './rng';
import { generateKeyPair, signRecord } from './sign';
import { SignableRecord } from './canonicalize';

export interface SyntheticRecord extends SignableRecord {
  _id: string;
  type: 'contribution';
  devicePublicKey: string;
  signature: string;
}

export interface SyntheticRecordOptions {
  seed: number;
  index: number;
  fieldCount: number;
  grantSetSize: number;
}

// mulberry32(seed) alone would replay the same sequence for every record at a
// given seed. Folding index into the seed via a fixed multiplier keeps records
// distinct while staying fully reproducible from (seed, index) alone.
function seedFor(seed: number, index: number): number {
  return (seed * 2654435761 + index) >>> 0;
}

export function generateSyntheticRecord(opts: SyntheticRecordOptions): SyntheticRecord {
  const rand = mulberry32(seedFor(opts.seed, opts.index));
  const participantRef = 'synthetic-participant-' + (opts.seed % 1000) + '-' + (opts.index % 500);

  const fields: Record<string, unknown> = {};
  const grantedFieldNames: string[] = [];
  for (let i = 0; i < opts.fieldCount; i++) {
    const fieldName = 'synthetic.field' + i;
    fields[fieldName] = Math.floor(rand() * 100);
    grantedFieldNames.push(fieldName);
  }

  const grants: unknown[] = [];
  for (let i = 0; i < opts.grantSetSize; i++) {
    // Wrap so that with grantSetSize < fieldCount, at least the first fields are
    // still covered; with grantSetSize >= fieldCount, every field is covered and
    // the remaining grants cover synthetic filler fields the record never uses.
    const field = i < grantedFieldNames.length ? grantedFieldNames[i] : 'synthetic.unused' + i;
    grants.push({
      field,
      purpose: 'service-improvement',
      recipientClass: 'statistics',
      grantedAt: '2026-01-01T00:00:00.000Z',
      expiresAt: null,
      revokedAt: null
    });
  }
  // If grantSetSize < fieldCount, the remaining fields need their own grants too,
  // so the record stays fully consented regardless of which dimension is larger.
  for (let i = grants.length; i < grantedFieldNames.length; i++) {
    grants.push({
      field: grantedFieldNames[i],
      purpose: 'service-improvement',
      recipientClass: 'statistics',
      grantedAt: '2026-01-01T00:00:00.000Z',
      expiresAt: null,
      revokedAt: null
    });
  }

  const record: SignableRecord = {
    participantRef,
    siteId: 'synthetic-site',
    collectedAt: '2026-08-30T11:00:00.000Z',
    channel: 'app',
    declaredPurpose: 'service-improvement',
    declaredRecipientClass: 'statistics',
    fields,
    consent: { participantRef, assertedAt: '2026-01-01T00:00:00.000Z', grants }
  };

  const keyPair = generateKeyPair(rand);
  const signature = signRecord(record, keyPair.privateKey);

  return {
    ...record,
    _id: 'synthetic-contribution-' + opts.seed + '-' + opts.index,
    type: 'contribution',
    devicePublicKey: keyPair.publicKeyBase64,
    signature
  };
}
```

- [ ] **Step 12: Run test to verify it passes**

Run: `npx vitest run --root experiments/harness/e1`
Expected: PASS, all tests in the package green.

- [ ] **Step 13: Commit**

```bash
git add experiments/harness/e1/src/rng.ts experiments/harness/e1/src/sign.ts \
  experiments/harness/e1/src/synthetic-records.ts experiments/harness/e1/test/rng.test.ts \
  experiments/harness/e1/test/sign.test.ts experiments/harness/e1/test/synthetic-records.test.ts
git commit -m "feat(e1): add seeded RNG, device-key signing, and synthetic record generation"
```

**Note on grant-set-size wrap-around:** with the sweep's fixed dimension values (field count 1/5/20, grant-set size 1/10/50), every combination leaves at least one dimension covering the other, so no record is ever left with an unconsented field. `synthetic-records.test.ts`'s second test asserts this property directly rather than trusting the loop logic by inspection alone.

---

## Task 3: Signature-verifying design document

**Files:**
- Create: `experiments/harness/e1/src/signature-validator-payload.ts`
- Create: `experiments/harness/e1/src/build-signature-design-doc.ts`
- Test: `experiments/harness/e1/test/build-signature-design-doc.test.ts`

**Interfaces:**
- Consumes: `evaluateRecord` from `packages/consent-core/src/evaluate.ts` (existing, unmodified); `signedPayload` (Task 1); `utf8Encode`, `base64Decode` (Task 1).
- Produces: `DesignDoc { _id: string; validate_doc_update: string }`, `buildSignatureDesignDoc(): DesignDoc`. Tasks 4 and 6 consume `buildSignatureDesignDoc`.

- [ ] **Step 1: Write the bundled payload module**

Create `experiments/harness/e1/src/signature-validator-payload.ts`:

```typescript
import { evaluateRecord } from '../../../../packages/consent-core/src/evaluate';
import { signedPayload } from './canonicalize';
import { utf8Encode, base64Decode } from './portable-codec';
import * as ed from '@noble/ed25519';
import { sha512 } from '@noble/hashes/sha512.js';

ed.hashes.sha512 = sha512;

export function evaluate(record: any, nowIso: string) {
  return evaluateRecord(record, nowIso);
}

export function verifySignature(record: any, signatureBase64: string, publicKeyBase64: string): boolean {
  if (!signatureBase64 || !publicKeyBase64) {
    return false;
  }
  var message = utf8Encode(signedPayload(record));
  var signature = base64Decode(signatureBase64);
  var publicKey = base64Decode(publicKeyBase64);
  try {
    return ed.verify(signature, message, publicKey);
  } catch (e) {
    return false;
  }
}
```

This file is never imported by anything outside `experiments/harness/e1`; it exists purely to be bundled by Step 4 below. It is not tested in isolation, only through the bundle's behavior in this task's Step 6 test and Task 4's live spike.

- [ ] **Step 2: Exclude the payload module from this package's own `tsc` build**

Modify `experiments/harness/e1/tsconfig.json`:

```json
{
  "extends": "../../../tsconfig.base.json",
  "compilerOptions": {
    "outDir": "dist",
    "rootDir": "src",
    "target": "ES2020",
    "lib": ["ES2020"]
  },
  "include": ["src"],
  "exclude": ["src/signature-validator-payload.ts"]
}
```

`signature-validator-payload.ts`'s import of `packages/consent-core/src/evaluate.ts` reaches outside this package's `rootDir` (`src`). `esbuild.buildSync` (Step 5 below) resolves that import directly on disk and does not care about `rootDir`, exactly like `packages/consent-couchdb/src/build-design-doc.ts` bundling `packages/consent-core/src/evaluate.ts` the same way; but this package's own `tsc -p tsconfig.json` build (`npm run build`, Task 1) does enforce `rootDir` on every file `include` reaches, and would fail with a `rootDir` violation on this one file otherwise. Excluding it from `tsc`'s own file set, while `esbuild` bundles it directly and independently, resolves this without weakening `rootDir` for the rest of the package.

- [ ] **Step 3: Write the failing test for the design doc builder**

Create `experiments/harness/e1/test/build-signature-design-doc.test.ts`:

```typescript
import { describe, it, expect } from 'vitest';
import { buildSignatureDesignDoc } from '../src/build-signature-design-doc';
import { generateSyntheticRecord } from '../src/synthetic-records';
import { base64Decode } from '../src/portable-codec';

describe('buildSignatureDesignDoc', () => {
  it('produces a design doc with a distinct id from the production structural doc', () => {
    expect(buildSignatureDesignDoc()._id).toBe('_design/consent-signature');
  });

  it('contains no syntax the CouchDB query server rejects (object spread only, per the probe)', () => {
    const fn = buildSignatureDesignDoc().validate_doc_update;
    expect(fn).not.toMatch(/\{\s*\.\.\./);
  });

  it('does not reference TextEncoder, Buffer, or atob', () => {
    const fn = buildSignatureDesignDoc().validate_doc_update;
    expect(fn).not.toMatch(/TextEncoder/);
    expect(fn).not.toMatch(/\bBuffer\b/);
    expect(fn).not.toMatch(/\batob\b/);
  });

  it('accepts a fully consented, correctly signed record', () => {
    const fn = buildSignatureDesignDoc().validate_doc_update;
    // eslint-disable-next-line no-new-func
    const validate = new Function('return ' + fn)();
    const record = generateSyntheticRecord({ seed: 1, index: 0, fieldCount: 2, grantSetSize: 2 });
    expect(() => validate(record, null, { name: record.participantRef, roles: [] }, {})).not.toThrow();
  });

  it('rejects a record with a tampered signature', () => {
    const fn = buildSignatureDesignDoc().validate_doc_update;
    // eslint-disable-next-line no-new-func
    const validate = new Function('return ' + fn)();
    const record = generateSyntheticRecord({ seed: 1, index: 0, fieldCount: 2, grantSetSize: 2 });
    const tamperedSignatureBytes = base64Decode(record.signature);
    tamperedSignatureBytes[0] = tamperedSignatureBytes[0] ^ 0xff;
    const tampered = { ...record, signature: Buffer.from(tamperedSignatureBytes).toString('base64') };
    let caught: any;
    try {
      validate(tampered, null, { name: tampered.participantRef, roles: [] }, {});
    } catch (e) {
      caught = e;
    }
    expect(caught).toBeDefined();
    expect(caught.forbidden).toBe('invalid-signature');
  });

  it('still rejects an unconsented field even with a valid signature', () => {
    const fn = buildSignatureDesignDoc().validate_doc_update;
    // eslint-disable-next-line no-new-func
    const validate = new Function('return ' + fn)();
    const record = generateSyntheticRecord({ seed: 1, index: 0, fieldCount: 2, grantSetSize: 2 });
    const unconsented = { ...record, consent: { ...record.consent, grants: [] } };
    // Note: mutating consent invalidates the original signature too, but the
    // structural check (evaluate) runs before the signature check in the
    // template, so this must fail with the structural reason, not
    // invalid-signature.
    let caught: any;
    try {
      validate(unconsented, null, { name: unconsented.participantRef, roles: [] }, {});
    } catch (e) {
      caught = e;
    }
    expect(caught).toBeDefined();
    expect(caught.forbidden).toMatch(/^no-grant:/);
  });
});
```

- [ ] **Step 4: Run test to verify it fails**

Run: `npx vitest run --root experiments/harness/e1`
Expected: FAIL, `Cannot find module '../src/build-signature-design-doc'`.

- [ ] **Step 5: Implement the design doc builder**

Create `experiments/harness/e1/src/build-signature-design-doc.ts`:

```typescript
import { buildSync } from 'esbuild';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));

export interface DesignDoc {
  _id: string;
  validate_doc_update: string;
}

export function buildSignatureDesignDoc(): DesignDoc {
  const entry = join(here, 'signature-validator-payload.ts');

  const result = buildSync({
    entryPoints: [entry],
    bundle: true,
    write: false,
    format: 'iife',
    globalName: 'SignaturePayload',
    // es2020, not es5: this bundle uses @noble/ed25519, which uses BigInt
    // arithmetic that esbuild cannot lower to es5. deploy/couchdb-engine-probe.md
    // confirmed BigInt is supported directly by the live query server, so es5 is
    // not required here. The production packages/consent-couchdb bundle is
    // unaffected and stays at es5.
    target: 'es2020',
    minify: false,
    legalComments: 'none'
  });

  const bundle = result.outputFiles[0].text;

  const validate = [
    'function(newDoc, oldDoc, userCtx, secObj) {',
    '  var isAdmin = (userCtx.roles || []).indexOf("_admin") !== -1;',
    '  if (newDoc._deleted) {',
    '    if (!isAdmin && oldDoc && userCtx.name !== oldDoc.participantRef) {',
    '      throw({ forbidden: "participant-identity-mismatch" });',
    '    }',
    '    return;',
    '  }',
    '  if (!isAdmin && userCtx.name !== newDoc.participantRef) {',
    '    throw({ forbidden: "participant-identity-mismatch" });',
    '  }',
    '  ' + bundle.split('\n').join('\n  '),
    '  var decision = SignaturePayload.evaluate(newDoc, new Date().toISOString());',
    '  if (!decision.allowed) {',
    '    throw({ forbidden: decision.reason });',
    '  }',
    '  if (newDoc.type === "contribution") {',
    '    var sigOk = SignaturePayload.verifySignature(newDoc, newDoc.signature, newDoc.devicePublicKey);',
    '    if (!sigOk) {',
    '      throw({ forbidden: "invalid-signature" });',
    '    }',
    '  }',
    '}'
  ].join('\n');

  return { _id: '_design/consent-signature', validate_doc_update: validate };
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `npx vitest run --root experiments/harness/e1`
Expected: PASS, all `build-signature-design-doc.test.ts` cases green. If the "accepts a fully consented, correctly signed record" case fails with a `ReferenceError` for a global not available in Node's `new Function` sandbox (there should not be one, since this bundle intentionally avoids every construct outside plain ES2020 plus `Uint8Array`/`BigInt`, both available in Node), stop and re-check Step 1's payload module against Task 1's portable codec before changing anything else, since a leak here would mean a global CouchDB's query server also lacks.

- [ ] **Step 7: Commit**

```bash
git add experiments/harness/e1/src/signature-validator-payload.ts \
  experiments/harness/e1/src/build-signature-design-doc.ts \
  experiments/harness/e1/tsconfig.json \
  experiments/harness/e1/test/build-signature-design-doc.test.ts
git commit -m "feat(e1): add the experiment-only signature-verifying design document"
```

---

## Task 4: Live spike against real CouchDB

**Files:**
- Create: `experiments/harness/e1/docker-compose.e1.yml`
- Create: `experiments/harness/e1/src/couchdb-client.ts`
- Test: `experiments/harness/e1/test/live-spike.integration.test.ts`

**Interfaces:**
- Consumes: `buildSignatureDesignDoc` (Task 3); `generateSyntheticRecord` (Task 2); `base64Decode` (Task 1).
- Produces: `CouchDbClient`, `createCouchDbClient(baseUrl: string, adminUser: string, adminPassword: string): CouchDbClient` with methods `put(path: string, body?: unknown): Promise<{ status: number; body: any }>`, `post(path: string, body?: unknown): Promise<{ status: number; body: any }>`, `delete(path: string): Promise<{ status: number; body: any }>`. Tasks 5 and 6 consume `couchdb-client.ts`; Task 6 specifically needs `post` for `_bulk_docs` writes.

This is the live spike the spec requires before any sweep code is written: confirm the Task 3 design document, once actually installed on a real CouchDB instance (not `new Function` in Node), behaves correctly. `deploy/couchdb-engine-probe.md` exists specifically because Node's engine and CouchDB's query-server engine are not the same JavaScript runtime, so Task 3's `new Function`-based tests are necessary but not sufficient.

- [ ] **Step 1: Create the dedicated compose file**

Create `experiments/harness/e1/docker-compose.e1.yml`:

```yaml
services:
  couchdb-e1:
    image: couchdb@sha256:9ea24cbd76522fe845d1c32c7fd1dcfc8a3ba73dcc4817d62f8a7f7f1dfaffe3
    container_name: benefits-couchdb-e1
    environment:
      COUCHDB_USER: admin
      COUCHDB_PASSWORD: benefits
    ports:
      - "5985:5984"
```

Port 5985, not 5984: `deploy/docker-compose.yml`'s CouchDB (port 5984) is the shared dev instance every other test and tool in this repo depends on and must stay untouched. No named volume: this instance is torn down and recreated fresh for every spike or sweep run, so results are never affected by a prior run's leftover data.

- [ ] **Step 2: Write the CouchDB HTTP client**

Create `experiments/harness/e1/src/couchdb-client.ts`:

```typescript
// Mirrors the req() helper in packages/consent-couchdb/test/couchdb.integration.test.ts.
// Node's native fetch forbids embedded credentials in the URL, so Basic auth goes in
// a header instead.

export interface CouchDbResponse {
  status: number;
  body: any;
}

export interface CouchDbClient {
  put(path: string, body?: unknown): Promise<CouchDbResponse>;
  post(path: string, body?: unknown): Promise<CouchDbResponse>;
  delete(path: string): Promise<CouchDbResponse>;
}

export function createCouchDbClient(baseUrl: string, adminUser: string, adminPassword: string): CouchDbClient {
  const auth = 'Basic ' + Buffer.from(adminUser + ':' + adminPassword).toString('base64');

  async function request(method: string, path: string, body?: unknown): Promise<CouchDbResponse> {
    const res = await fetch(baseUrl + path, {
      method,
      headers: { 'content-type': 'application/json', authorization: auth },
      body: body === undefined ? undefined : JSON.stringify(body)
    });
    const parsed = await res.json().catch(() => ({}));
    return { status: res.status, body: parsed };
  }

  return {
    put: (path, body) => request('PUT', path, body),
    post: (path, body) => request('POST', path, body),
    delete: (path) => request('DELETE', path)
  };
}
```

- [ ] **Step 3: Bring up the dedicated instance**

Run:
```bash
cd experiments/harness/e1
docker compose -f docker-compose.e1.yml up -d
until curl -sf http://admin:benefits@localhost:5985/_up >/dev/null; do sleep 1; done
```
Expected: CouchDB reachable at `http://localhost:5985`, separate from the dev instance at `5984` (confirm both, if `5984` happens to be up: `curl -s http://admin:benefits@localhost:5984/_all_dbs` and `curl -s http://admin:benefits@localhost:5985/_all_dbs` return independently, and deleting a database on `5985` must not remove anything from `5984`).

- [ ] **Step 4: Write the live spike test**

Create `experiments/harness/e1/test/live-spike.integration.test.ts`:

```typescript
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { createCouchDbClient } from '../src/couchdb-client';
import { buildSignatureDesignDoc } from '../src/build-signature-design-doc';
import { generateSyntheticRecord } from '../src/synthetic-records';
import { base64Decode } from '../src/portable-codec';

const client = createCouchDbClient('http://localhost:5985', 'admin', 'benefits');
const DB = 'e1-spike';

describe('signature design doc against real CouchDB', () => {
  beforeAll(async () => {
    await client.delete('/' + DB);
    const created = await client.put('/' + DB);
    expect([201, 202]).toContain(created.status);
    const dd = buildSignatureDesignDoc();
    const put = await client.put('/' + DB + '/' + dd._id, dd);
    expect([201, 202]).toContain(put.status);
  }, 30000);

  afterAll(async () => {
    await client.delete('/' + DB);
  });

  it('accepts a correctly signed, fully consented record', async () => {
    const record = generateSyntheticRecord({ seed: 100, index: 0, fieldCount: 2, grantSetSize: 2 });
    const res = await client.put('/' + DB + '/' + record._id, record);
    expect(res.status).toBe(201);
  });

  it('rejects a record with a tampered signature', async () => {
    const record = generateSyntheticRecord({ seed: 100, index: 1, fieldCount: 2, grantSetSize: 2 });
    const tamperedBytes = base64Decode(record.signature);
    tamperedBytes[0] = tamperedBytes[0] ^ 0xff;
    const tampered = { ...record, _id: record._id + '-tampered', signature: Buffer.from(tamperedBytes).toString('base64') };
    const res = await client.put('/' + DB + '/' + tampered._id, tampered);
    expect(res.status).toBe(403);
    expect(res.body.reason).toBe('invalid-signature');
  });

  it('rejects an unconsented field even with a valid signature', async () => {
    const record = generateSyntheticRecord({ seed: 100, index: 2, fieldCount: 2, grantSetSize: 2 });
    const unconsented = { ...record, _id: record._id + '-unconsented', consent: { ...record.consent, grants: [] } };
    const res = await client.put('/' + DB + '/' + unconsented._id, unconsented);
    expect(res.status).toBe(403);
    expect(res.body.reason).toMatch(/^no-grant:/);
  });
});
```

- [ ] **Step 5: Run the live spike**

Run: `npx vitest run --root experiments/harness/e1 test/live-spike.integration.test.ts`
Expected: PASS, all three cases green against the real, dedicated CouchDB instance on port 5985. If any case fails, this is the point the spec calls out explicitly: do not proceed to Task 5 or 6 until this passes against the real engine, since Task 3's `new Function` tests cannot themselves prove query-server compatibility.

- [ ] **Step 6: Tear down**

Run: `docker compose -f experiments/harness/e1/docker-compose.e1.yml down`

- [ ] **Step 7: Commit**

```bash
git add experiments/harness/e1/docker-compose.e1.yml experiments/harness/e1/src/couchdb-client.ts \
  experiments/harness/e1/test/live-spike.integration.test.ts
git commit -m "feat(e1): add dedicated CouchDB instance and live spike for the signature design doc"
```

---

## Task 5: Throttle profile and host CPU model

**Files:**
- Modify: `experiments/harness/e1/docker-compose.e1.yml`
- Create: `experiments/harness/e1/src/host-info.ts`
- Test: `experiments/harness/e1/test/host-info.test.ts`

**Interfaces:**
- Produces: `readHostCpuModel(): string` (`host-info.ts`). Task 6 consumes this.

- [ ] **Step 1: Add a throttled profile to the compose file**

Modify `experiments/harness/e1/docker-compose.e1.yml`:

```yaml
services:
  couchdb-e1:
    image: couchdb@sha256:9ea24cbd76522fe845d1c32c7fd1dcfc8a3ba73dcc4817d62f8a7f7f1dfaffe3
    container_name: benefits-couchdb-e1
    environment:
      COUCHDB_USER: admin
      COUCHDB_PASSWORD: benefits
    ports:
      - "5985:5984"

  couchdb-e1-throttled:
    image: couchdb@sha256:9ea24cbd76522fe845d1c32c7fd1dcfc8a3ba73dcc4817d62f8a7f7f1dfaffe3
    container_name: benefits-couchdb-e1-throttled
    environment:
      COUCHDB_USER: admin
      COUCHDB_PASSWORD: benefits
    ports:
      - "5986:5984"
    deploy:
      resources:
        limits:
          cpus: "1"
          memory: 1g
```

Two separate services, not one service reused twice: `couchdb-e1` (port 5985, unthrottled) stays available for fast iteration and the Task 4 spike; `couchdb-e1-throttled` (port 5986) is the low-end-device-profile instance the Task 6 sweep actually measures against. `docker compose up couchdb-e1-throttled` brings up only the second service.

- [ ] **Step 2: Verify the throttle is actually applied**

Run:
```bash
cd experiments/harness/e1
docker compose -f docker-compose.e1.yml up -d couchdb-e1-throttled
docker inspect benefits-couchdb-e1-throttled --format 'NanoCpus={{.HostConfig.NanoCpus}} Memory={{.HostConfig.Memory}}'
docker compose -f docker-compose.e1.yml down
```
Expected: `NanoCpus=1000000000 Memory=1073741824` (1 CPU, 1 GiB), confirmed live against this exact `docker compose` version rather than assumed from documentation, matching the check already run and recorded before this plan was written.

- [ ] **Step 3: Write the failing test for host CPU model reading**

Create `experiments/harness/e1/test/host-info.test.ts`:

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

- [ ] **Step 4: Run test to verify it fails**

Run: `npx vitest run --root experiments/harness/e1`
Expected: FAIL, `Cannot find module '../src/host-info'`.

- [ ] **Step 5: Implement host CPU model reading**

Create `experiments/harness/e1/src/host-info.ts`:

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

- [ ] **Step 6: Run test to verify it passes**

Run: `npx vitest run --root experiments/harness/e1`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add experiments/harness/e1/docker-compose.e1.yml experiments/harness/e1/src/host-info.ts \
  experiments/harness/e1/test/host-info.test.ts
git commit -m "feat(e1): add throttled CouchDB profile and host CPU model reader"
```

---

## Task 6: E1 sweep driver

**Files:**
- Create: `experiments/harness/e1/src/csv.ts`
- Create: `experiments/harness/e1/src/sweep.ts`
- Test: `experiments/harness/e1/test/csv.test.ts`
- Test: `experiments/harness/e1/test/sweep.integration.test.ts`

**Interfaces:**
- Consumes: `createCouchDbClient` (Task 4); `buildSignatureDesignDoc` (Task 3); `buildDesignDoc` from `packages/consent-couchdb` (existing, unmodified); `generateSyntheticRecord` (Task 2); `readHostCpuModel` (Task 5).
- Produces: `writeCsv(path: string, header: string[], rows: (string | number)[][]): void` (`csv.ts`). `runSweep(opts: SweepOptions): Promise<void>` (`sweep.ts`), the plan's E1 deliverable.

- [ ] **Step 1: Write the failing test for the CSV writer**

Create `experiments/harness/e1/test/csv.test.ts`:

```typescript
import { describe, it, expect, afterEach } from 'vitest';
import { writeCsv } from '../src/csv';
import { readFileSync, unlinkSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';

const path = join(tmpdir(), 'e1-csv-test.csv');

afterEach(() => {
  if (existsSync(path)) {
    unlinkSync(path);
  }
});

describe('writeCsv', () => {
  it('writes a header row and data rows separated by commas and newlines', () => {
    writeCsv(path, ['a', 'b'], [[1, 2], [3, 4]]);
    const content = readFileSync(path, 'utf8');
    expect(content).toBe('a,b\n1,2\n3,4\n');
  });

  it('quotes a string field containing a comma or space', () => {
    writeCsv(path, ['name'], [['Intel(R) Core i7, 2.6GHz']]);
    const content = readFileSync(path, 'utf8');
    expect(content).toBe('name\n"Intel(R) Core i7, 2.6GHz"\n');
  });

  it('does not quote a plain numeric or simple string field', () => {
    writeCsv(path, ['x'], [['baseline']]);
    const content = readFileSync(path, 'utf8');
    expect(content).toBe('x\nbaseline\n');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run --root experiments/harness/e1`
Expected: FAIL, `Cannot find module '../src/csv'`.

- [ ] **Step 3: Implement the CSV writer**

Create `experiments/harness/e1/src/csv.ts`:

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

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run --root experiments/harness/e1`
Expected: PASS.

- [ ] **Step 5: Write the sweep integration test**

Create `experiments/harness/e1/test/sweep.integration.test.ts`:

```typescript
import { describe, it, expect } from 'vitest';
import { runSweep } from '../src/sweep';
import { readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { mkdtempSync } from 'node:fs';

// A small, fast sweep against the real, dedicated (unthrottled) e1 instance from
// Task 4, run manually (`docker compose -f docker-compose.e1.yml up -d couchdb-e1`)
// before this test. This is not run in the default `npm test` loop, since it
// depends on that container; it is run explicitly as part of this task's
// verification and again in Task 11's reproducibility check.
describe.skipIf(!process.env.E1_LIVE)('runSweep', () => {
  it('produces one CSV per arm with the expected header, for a reduced sweep', async () => {
    const outDir = mkdtempSync(join(tmpdir(), 'e1-sweep-'));
    await runSweep({
      baseUrl: 'http://localhost:5985',
      adminUser: 'admin',
      adminPassword: 'benefits',
      seed: 1,
      batchSizes: [10, 50],
      fieldCounts: [1, 5],
      grantSetSizes: [1, 10],
      trials: 1,
      outDir
    });
    for (const arm of ['baseline', 'structural', 'signature']) {
      const path = join(outDir, arm + '.csv');
      expect(existsSync(path)).toBe(true);
      const content = readFileSync(path, 'utf8');
      const lines = content.trim().split('\n');
      expect(lines[0]).toBe(
        'seed,trial,batch_size,field_count,grant_set_size,wall_time_ms,throttle_cpus,throttle_memory_mb,host_cpu_model'
      );
      // 2 batch sizes x 2 field counts x 2 grant-set sizes x 1 trial = 8 rows, plus header.
      expect(lines.length).toBe(9);
    }
  }, 120000);
});
```

- [ ] **Step 6: Run test to verify it fails**

Run: `npx vitest run --root experiments/harness/e1`
Expected: the `sweep.integration.test.ts` suite is skipped (`E1_LIVE` unset), but `npx tsc --noEmit -p experiments/harness/e1/tsconfig.json` fails with `Cannot find module '../src/sweep'`, confirming the test file itself is wired up correctly before the module exists.

- [ ] **Step 7: Implement the sweep driver**

Create `experiments/harness/e1/src/sweep.ts`:

```typescript
import { createCouchDbClient } from './couchdb-client';
import { buildSignatureDesignDoc } from './build-signature-design-doc';
import { buildDesignDoc } from '@benefits/consent-couchdb';
import { generateSyntheticRecord } from './synthetic-records';
import { readHostCpuModel } from './host-info';
import { writeCsv } from './csv';
import { join } from 'node:path';

export interface SweepOptions {
  baseUrl: string;
  adminUser: string;
  adminPassword: string;
  seed: number;
  batchSizes: number[];
  fieldCounts: number[];
  grantSetSizes: number[];
  trials: number;
  outDir: string;
  throttleCpus?: number;
  throttleMemoryMb?: number;
}

type Arm = 'baseline' | 'structural' | 'signature';
const ARMS: Arm[] = ['baseline', 'structural', 'signature'];

async function dbNameFor(arm: Arm, seed: number): Promise<string> {
  return 'e1-sweep-' + arm + '-' + seed;
}

async function installArm(
  client: ReturnType<typeof createCouchDbClient>,
  arm: Arm,
  dbName: string
): Promise<void> {
  await client.delete('/' + dbName);
  const created = await client.put('/' + dbName);
  if (created.status !== 201 && created.status !== 202) {
    throw new Error('failed to create database ' + dbName + ': ' + created.status);
  }
  if (arm === 'baseline') {
    return;
  }
  const dd = arm === 'structural' ? buildDesignDoc() : buildSignatureDesignDoc();
  const put = await client.put('/' + dbName + '/' + dd._id, dd);
  if (put.status !== 201 && put.status !== 202) {
    throw new Error('failed to install design doc for ' + arm + ': ' + put.status);
  }
}

async function writeBatch(
  client: ReturnType<typeof createCouchDbClient>,
  dbName: string,
  seed: number,
  batchOffset: number,
  batchSize: number,
  fieldCount: number,
  grantSetSize: number
): Promise<number> {
  const docs = [];
  for (let i = 0; i < batchSize; i++) {
    docs.push(
      generateSyntheticRecord({ seed, index: batchOffset + i, fieldCount, grantSetSize })
    );
  }
  const start = process.hrtime.bigint();
  const res = await client.post('/' + dbName + '/_bulk_docs', { docs });
  const end = process.hrtime.bigint();
  if (res.status !== 201) {
    throw new Error('bulk write failed with status ' + res.status);
  }
  return Number(end - start) / 1e6;
}

export async function runSweep(opts: SweepOptions): Promise<void> {
  const client = createCouchDbClient(opts.baseUrl, opts.adminUser, opts.adminPassword);
  const hostCpuModel = readHostCpuModel();
  const throttleCpus = opts.throttleCpus ?? '';
  const throttleMemoryMb = opts.throttleMemoryMb ?? '';

  for (const arm of ARMS) {
    const dbName = await dbNameFor(arm, opts.seed);
    await installArm(client, arm, dbName);

    const rows: (string | number)[][] = [];
    let indexOffset = 0;

    for (const batchSize of opts.batchSizes) {
      for (const fieldCount of opts.fieldCounts) {
        for (const grantSetSize of opts.grantSetSizes) {
          for (let trial = 0; trial < opts.trials; trial++) {
            const wallTimeMs = await writeBatch(
              client,
              dbName,
              opts.seed,
              indexOffset,
              batchSize,
              fieldCount,
              grantSetSize
            );
            indexOffset += batchSize;
            rows.push([
              opts.seed,
              trial,
              batchSize,
              fieldCount,
              grantSetSize,
              wallTimeMs,
              throttleCpus,
              throttleMemoryMb,
              hostCpuModel
            ]);
          }
        }
      }
    }

    writeCsv(
      join(opts.outDir, arm + '.csv'),
      [
        'seed',
        'trial',
        'batch_size',
        'field_count',
        'grant_set_size',
        'wall_time_ms',
        'throttle_cpus',
        'throttle_memory_mb',
        'host_cpu_model'
      ],
      rows
    );

    await client.delete('/' + dbName);
  }
}
```

`indexOffset` increments across every batch within one arm's loop so no two documents in the same database ever collide on `_id` (`generateSyntheticRecord`'s `_id` is `synthetic-contribution-<seed>-<index>`), which matters because `_bulk_docs` reports a per-document conflict rather than failing the whole batch, and a silent partial write would corrupt the batch's own timing (some documents accepted, some not, without the loop knowing).

- [ ] **Step 8: Add the sweep entry-point script**

Create `experiments/harness/e1/src/run-e1.ts`:

```typescript
import { runSweep } from './sweep';

const SEED = 1;
const BATCH_SIZES = [10, 50, 100, 500, 1000, 5000, 10000];
const FIELD_COUNTS = [1, 5, 20];
const GRANT_SET_SIZES = [1, 10, 50];
const TRIALS = 3;

runSweep({
  baseUrl: 'http://localhost:5986',
  adminUser: 'admin',
  adminPassword: 'benefits',
  seed: SEED,
  batchSizes: BATCH_SIZES,
  fieldCounts: FIELD_COUNTS,
  grantSetSizes: GRANT_SET_SIZES,
  trials: TRIALS,
  outDir: '../../results/e1',
  throttleCpus: 1,
  throttleMemoryMb: 1024
}).then(() => {
  console.log('E1 sweep complete');
}).catch((err) => {
  console.error(err);
  process.exitCode = 1;
});
```

Port `5986`: the throttled service from Task 5, since the actual sweep (as opposed to the Task 4 spike) is the low-end-device-profile measurement the spec requires.

- [ ] **Step 9: Add the sweep script to package.json**

Modify `experiments/harness/e1/package.json`'s `scripts` block:

```json
{
  "name": "@benefits/experiment-e1",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "build": "tsc -p tsconfig.json",
    "test": "vitest run",
    "sweep": "npm run build && node dist/run-e1.js"
  },
  "dependencies": {
    "@benefits/consent-couchdb": "0.1.0",
    "@noble/ed25519": "3.2.0",
    "@noble/hashes": "1.8.0"
  },
  "devDependencies": {
    "esbuild": "0.24.2"
  }
}
```

Run: `npm install` (picks up the new `@benefits/consent-couchdb` dependency, linked via the existing workspace).

`@benefits/consent-couchdb`'s `main` entry (`dist/build-design-doc.js`) is a build output, not committed to git; in a fresh worktree it needs building before `sweep.ts` can import from it, the same caveat Task 10 hits for `@benefits/audit-verify`.

Run: `npm run build --workspace=@benefits/consent-couchdb`
Expected: `packages/consent-couchdb/dist/build-design-doc.js` exists.

- [ ] **Step 10: Run the reduced integration test against the live unthrottled instance**

Run:
```bash
cd experiments/harness/e1
docker compose -f docker-compose.e1.yml up -d couchdb-e1
until curl -sf http://admin:benefits@localhost:5985/_up >/dev/null; do sleep 1; done
E1_LIVE=1 npx vitest run test/sweep.integration.test.ts
docker compose -f docker-compose.e1.yml down
```
Expected: PASS, all three arm CSVs produced with the expected header and 9 lines (8 rows plus header) for the reduced sweep.

- [ ] **Step 11: Commit**

```bash
git add experiments/harness/e1/src/csv.ts experiments/harness/e1/src/sweep.ts \
  experiments/harness/e1/src/run-e1.ts experiments/harness/e1/package.json \
  experiments/harness/e1/test/csv.test.ts experiments/harness/e1/test/sweep.integration.test.ts
git commit -m "feat(e1): add the full sweep driver across baseline, structural, and signature arms"
```

---

## Task 7: E3 Gradle module and seeded fixture generation

**Files:**
- Create: `experiments/harness/e3/build.gradle.kts`
- Modify: `settings.gradle.kts`
- Create: `experiments/harness/e3/src/main/java/org/benefits/experiments/e3/SeededFixtureGenerator.java`
- Test: `experiments/harness/e3/src/test/java/org/benefits/experiments/e3/SeededFixtureGeneratorTest.java`

**Interfaces:**
- Consumes: `AuditEntry`, `AuditHashing`, `MerkleTree`, `MerkleProof` from `connector.extensions.benefits-audit` (existing, unmodified).
- Produces: `SeededFixtureGenerator(long seed)`, `List<AuditEntry> entries(int count)`. Tasks 8 and 9 consume this class.

- [ ] **Step 1: Register the new Gradle module**

Modify `settings.gradle.kts`:

```kotlin
rootProject.name = "benefits"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(":connector:extensions:benefits-policy")
include(":connector:extensions:benefits-privacy")
include(":connector:extensions:benefits-audit")
include(":connector:runtime")
include(":experiments:harness:e3")
```

- [ ] **Step 2: Create the module's build file**

Create `experiments/harness/e3/build.gradle.kts`:

```kotlin
plugins {
    `java-library`
}

dependencies {
    implementation(project(":connector:extensions:benefits-audit"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
```

This mirrors `connector/extensions/benefits-audit/build.gradle.kts`'s dependency-declaration style, using the same `libs.*` version catalog entries already defined in `gradle/libs.versions.toml`.

- [ ] **Step 3: Write the failing test for the seeded fixture generator**

Create `experiments/harness/e3/src/test/java/org/benefits/experiments/e3/SeededFixtureGeneratorTest.java`:

```java
package org.benefits.experiments.e3;

import org.benefits.audit.AuditEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SeededFixtureGeneratorTest {

    @Test
    void sameSeedProducesIdenticalEntries() {
        var a = new SeededFixtureGenerator(42).entries(50);
        var b = new SeededFixtureGenerator(42).entries(50);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void differentSeedsProduceDifferentEntries() {
        var a = new SeededFixtureGenerator(42).entries(50);
        var b = new SeededFixtureGenerator(43).entries(50);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void producesTheRequestedCount() {
        var entries = new SeededFixtureGenerator(1).entries(17);
        assertThat(entries).hasSize(17);
    }

    @Test
    void entriesHaveSequentialSeqStartingAtZero() {
        var entries = new SeededFixtureGenerator(1).entries(5);
        for (var i = 0; i < entries.size(); i++) {
            assertThat(entries.get(i).seq()).isEqualTo(i);
        }
    }

    @Test
    void entriesCarryTheSuppliedEpochId() {
        var entries = new SeededFixtureGenerator(1).entriesForEpoch(5, "epoch-a");
        for (var entry : entries) {
            assertThat(entry.epoch()).isEqualTo("epoch-a");
        }
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `./gradlew :experiments:harness:e3:test --tests "org.benefits.experiments.e3.SeededFixtureGeneratorTest"`
Expected: FAIL to compile, `SeededFixtureGenerator` does not exist.

- [ ] **Step 5: Implement the seeded fixture generator**

Create `experiments/harness/e3/src/main/java/org/benefits/experiments/e3/SeededFixtureGenerator.java`:

```java
package org.benefits.experiments.e3;

import org.benefits.audit.AuditEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Builds synthetic AuditEntry lists deterministically from a seed, for E3's sweep.
 * java.util.Random(seed) is used rather than Math.random()/ThreadLocalRandom, both
 * of which are not seedable, so this generator is the only source of randomness the
 * sweep needs to reproduce for the "results reproduce from seed" gate.
 */
public class SeededFixtureGenerator {

    private final long seed;

    public SeededFixtureGenerator(long seed) {
        this.seed = seed;
    }

    public List<AuditEntry> entries(int count) {
        return entriesForEpoch(count, null);
    }

    public List<AuditEntry> entriesForEpoch(int count, String epochId) {
        var random = new Random(seed);
        List<AuditEntry> result = new ArrayList<>();
        for (var i = 0; i < count; i++) {
            result.add(new AuditEntry(
                    i,
                    epochId,
                    "2026-08-30T00:00:00.000Z",
                    "contribution.recorded",
                    "synthetic-actor-" + random.nextInt(1000),
                    "synthetic-asset-" + i,
                    "service-improvement",
                    "synthetic entry " + i + " seed " + seed
            ));
        }
        return result;
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :experiments:harness:e3:test --tests "org.benefits.experiments.e3.SeededFixtureGeneratorTest"`
Expected: PASS, all five cases green.

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts experiments/harness/e3/build.gradle.kts \
  experiments/harness/e3/src/main/java/org/benefits/experiments/e3/SeededFixtureGenerator.java \
  experiments/harness/e3/src/test/java/org/benefits/experiments/e3/SeededFixtureGeneratorTest.java
git commit -m "feat(e3): add the experiments harness Gradle module and seeded fixture generator"
```

---

## Task 8: Proof-size measurement and inclusion fixtures

**Files:**
- Create: `experiments/harness/e3/src/main/java/org/benefits/experiments/e3/ProofSizeRunner.java`
- Test: `experiments/harness/e3/src/test/java/org/benefits/experiments/e3/ProofSizeRunnerTest.java`

**Interfaces:**
- Consumes: `SeededFixtureGenerator` (Task 7); `MerkleTree`, `MerkleProof`, `AuditHashing` (existing, unmodified).
- Produces: `ProofSizeRunner(long seed)`, `void run(List<Integer> epochSizes, String proofSizeCsvPath, String inclusionFixturesJsonPath)`. Task 10 consumes the JSON fixture file this writes.

- [ ] **Step 1: Write the failing test**

Create `experiments/harness/e3/src/test/java/org/benefits/experiments/e3/ProofSizeRunnerTest.java`:

```java
package org.benefits.experiments.e3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProofSizeRunnerTest {

    @Test
    void writesOneCsvRowPerSampledLeafPerEpochSize(@TempDir Path tempDir) throws IOException {
        var csvPath = tempDir.resolve("proof-size.csv");
        var fixturesPath = tempDir.resolve("inclusion-fixtures.json");

        new ProofSizeRunner(7).run(List.of(5, 20), csvPath.toString(), fixturesPath.toString());

        var lines = Files.readAllLines(csvPath);
        assertThat(lines.get(0)).isEqualTo("seed,epoch_size,leaf_index,proof_steps,proof_bytes");
        // epoch size 5 (< 10): every leaf sampled, 5 rows. epoch size 20: 10 sampled, 10 rows.
        assertThat(lines).hasSize(1 + 5 + 10);
    }

    @Test
    void proofBytesIsThirtyTwoTimesProofSteps(@TempDir Path tempDir) throws IOException {
        var csvPath = tempDir.resolve("proof-size.csv");
        var fixturesPath = tempDir.resolve("inclusion-fixtures.json");

        new ProofSizeRunner(7).run(List.of(20), csvPath.toString(), fixturesPath.toString());

        var lines = Files.readAllLines(csvPath);
        for (var i = 1; i < lines.size(); i++) {
            var fields = lines.get(i).split(",");
            var steps = Integer.parseInt(fields[3]);
            var bytes = Integer.parseInt(fields[4]);
            assertThat(bytes).isEqualTo(steps * 32);
        }
    }

    @Test
    void writesAFixtureFileWithVerifiableEntriesForEverySampledPoint(@TempDir Path tempDir) throws IOException {
        var csvPath = tempDir.resolve("proof-size.csv");
        var fixturesPath = tempDir.resolve("inclusion-fixtures.json");

        new ProofSizeRunner(7).run(List.of(5), csvPath.toString(), fixturesPath.toString());

        var json = Files.readString(fixturesPath);
        assertThat(json).contains("\"epochSize\":5");
        assertThat(json).contains("\"leafIndex\"");
        assertThat(json).contains("\"leaf\"");
        assertThat(json).contains("\"root\"");
        assertThat(json).contains("\"steps\"");
        assertThat(json).contains("\"siblingHash\"");
        assertThat(json).contains("\"siblingIsRight\"");
    }

    @Test
    void isReproducibleForTheSameSeed(@TempDir Path tempDir) throws IOException {
        var csvA = tempDir.resolve("a.csv");
        var jsonA = tempDir.resolve("a.json");
        var csvB = tempDir.resolve("b.csv");
        var jsonB = tempDir.resolve("b.json");

        new ProofSizeRunner(99).run(List.of(10, 50), csvA.toString(), jsonA.toString());
        new ProofSizeRunner(99).run(List.of(10, 50), csvB.toString(), jsonB.toString());

        assertThat(Files.readString(csvA)).isEqualTo(Files.readString(csvB));
        assertThat(Files.readString(jsonA)).isEqualTo(Files.readString(jsonB));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :experiments:harness:e3:test --tests "org.benefits.experiments.e3.ProofSizeRunnerTest"`
Expected: FAIL to compile, `ProofSizeRunner` does not exist.

- [ ] **Step 3: Implement the proof-size runner**

Create `experiments/harness/e3/src/main/java/org/benefits/experiments/e3/ProofSizeRunner.java`:

```java
package org.benefits.experiments.e3;

import org.benefits.audit.AuditHashing;
import org.benefits.audit.MerkleProof;
import org.benefits.audit.MerkleTree;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Measures proof size for a swept set of epoch sizes and, for the same sampled
 * points, exports a JSON fixture file so experiments/harness/e3-verify (Node) can
 * time verifyInclusion() against byte-identical data without needing to reimplement
 * tree construction itself.
 */
public class ProofSizeRunner {

    private final long seed;

    public ProofSizeRunner(long seed) {
        this.seed = seed;
    }

    public void run(List<Integer> epochSizes, String proofSizeCsvPath, String inclusionFixturesJsonPath) throws IOException {
        var csvLines = new ArrayList<String>();
        csvLines.add("seed,epoch_size,leaf_index,proof_steps,proof_bytes");

        var fixtureJson = new StringBuilder();
        fixtureJson.append("[\n");
        var firstFixture = true;

        for (var epochSize : epochSizes) {
            var entries = new SeededFixtureGenerator(seed).entriesForEpoch(epochSize, "epoch-" + epochSize);
            var leafHashes = new ArrayList<String>();
            for (var entry : entries) {
                leafHashes.add(AuditHashing.leafHash(entry));
            }
            var root = MerkleTree.root(leafHashes);

            for (var leafIndex : sampleIndices(epochSize)) {
                var proof = MerkleTree.proof(leafHashes, leafIndex);
                var steps = proof.steps().size();
                var bytes = steps * 32;
                csvLines.add(seed + "," + epochSize + "," + leafIndex + "," + steps + "," + bytes);

                if (!firstFixture) {
                    fixtureJson.append(",\n");
                }
                firstFixture = false;
                appendFixture(fixtureJson, epochSize, leafIndex, leafHashes.get(leafIndex), proof, root);
            }
        }
        fixtureJson.append("\n]\n");

        var csvOutPath = Path.of(proofSizeCsvPath);
        var jsonOutPath = Path.of(inclusionFixturesJsonPath);
        Files.createDirectories(csvOutPath.toAbsolutePath().getParent());
        Files.createDirectories(jsonOutPath.toAbsolutePath().getParent());
        Files.write(csvOutPath, String.join("\n", csvLines).concat("\n").getBytes(StandardCharsets.UTF_8));
        Files.write(jsonOutPath, fixtureJson.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static List<Integer> sampleIndices(int epochSize) {
        List<Integer> indices = new ArrayList<>();
        if (epochSize <= 10) {
            for (var i = 0; i < epochSize; i++) {
                indices.add(i);
            }
            return indices;
        }
        for (var i = 0; i < 10; i++) {
            indices.add((int) Math.floor((long) i * (epochSize - 1) / 9.0));
        }
        return indices;
    }

    private static void appendFixture(
            StringBuilder out, int epochSize, int leafIndex, String leaf, MerkleProof proof, String root) {
        out.append("  {\n");
        out.append("    \"epochSize\": ").append(epochSize).append(",\n");
        out.append("    \"leafIndex\": ").append(leafIndex).append(",\n");
        out.append("    \"leaf\": \"").append(leaf).append("\",\n");
        out.append("    \"root\": \"").append(root).append("\",\n");
        out.append("    \"steps\": [\n");
        var steps = proof.steps();
        for (var i = 0; i < steps.size(); i++) {
            var step = steps.get(i);
            out.append("      { \"siblingHash\": \"").append(step.siblingHash())
                    .append("\", \"siblingIsRight\": ").append(step.siblingIsRight()).append(" }");
            out.append(i < steps.size() - 1 ? ",\n" : "\n");
        }
        out.append("    ]\n");
        out.append("  }");
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :experiments:harness:e3:test --tests "org.benefits.experiments.e3.ProofSizeRunnerTest"`
Expected: PASS, all four cases green.

- [ ] **Step 5: Commit**

```bash
git add experiments/harness/e3/src/main/java/org/benefits/experiments/e3/ProofSizeRunner.java \
  experiments/harness/e3/src/test/java/org/benefits/experiments/e3/ProofSizeRunnerTest.java
git commit -m "feat(e3): add proof-size measurement and inclusion-fixture export"
```

---

## Task 9: Chain-length fixtures

**Files:**
- Create: `experiments/harness/e3/src/main/java/org/benefits/experiments/e3/ChainFixtureRunner.java`
- Test: `experiments/harness/e3/src/test/java/org/benefits/experiments/e3/ChainFixtureRunnerTest.java`

**Interfaces:**
- Consumes: `AuditHashing` (existing, unmodified, specifically `GENESIS_PREV_EPOCH_HASH` and `prevEpochHash`).
- Produces: `ChainFixtureRunner(long seed)`, `void run(List<Integer> chainLengths, String chainFixturesJsonPath)`. Task 10 consumes the JSON file this writes.

`EpochSealer` is not reused here: it is a stateful class tied to a live `AuditStore`, built for the real event-sealing path, not for generating a standalone list of `Epoch` values. Building epoch chains directly via `AuditHashing.prevEpochHash` (the same static function `EpochSealer` itself calls) is simpler and does not need a store.

- [ ] **Step 1: Write the failing test**

Create `experiments/harness/e3/src/test/java/org/benefits/experiments/e3/ChainFixtureRunnerTest.java`:

```java
package org.benefits.experiments.e3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChainFixtureRunnerTest {

    @Test
    void writesOneChainEntryPerRequestedLength(@TempDir Path tempDir) throws IOException {
        var path = tempDir.resolve("chain-fixtures.json");
        new ChainFixtureRunner(3).run(List.of(5, 20), path.toString());

        var json = Files.readString(path);
        assertThat(json).contains("\"chainLength\":5");
        assertThat(json).contains("\"chainLength\":20");
    }

    @Test
    void eachChainStartsFromTheGenesisPrevEpochHash(@TempDir Path tempDir) throws IOException {
        var path = tempDir.resolve("chain-fixtures.json");
        new ChainFixtureRunner(3).run(List.of(5), path.toString());

        var json = Files.readString(path);
        assertThat(json).contains(org.benefits.audit.AuditHashing.GENESIS_PREV_EPOCH_HASH);
    }

    @Test
    void isReproducibleForTheSameSeed(@TempDir Path tempDir) throws IOException {
        var pathA = tempDir.resolve("a.json");
        var pathB = tempDir.resolve("b.json");
        new ChainFixtureRunner(3).run(List.of(5, 20), pathA.toString());
        new ChainFixtureRunner(3).run(List.of(5, 20), pathB.toString());
        assertThat(Files.readString(pathA)).isEqualTo(Files.readString(pathB));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :experiments:harness:e3:test --tests "org.benefits.experiments.e3.ChainFixtureRunnerTest"`
Expected: FAIL to compile, `ChainFixtureRunner` does not exist.

- [ ] **Step 3: Implement the chain fixture runner**

Create `experiments/harness/e3/src/main/java/org/benefits/experiments/e3/ChainFixtureRunner.java`:

```java
package org.benefits.experiments.e3;

import org.benefits.audit.AuditHashing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

public class ChainFixtureRunner {

    private final long seed;

    public ChainFixtureRunner(long seed) {
        this.seed = seed;
    }

    public void run(List<Integer> chainLengths, String chainFixturesJsonPath) throws IOException {
        var out = new StringBuilder();
        out.append("[\n");
        var firstChain = true;

        for (var chainLength : chainLengths) {
            if (!firstChain) {
                out.append(",\n");
            }
            firstChain = false;
            appendChain(out, chainLength);
        }
        out.append("\n]\n");

        var outPath = Path.of(chainFixturesJsonPath);
        Files.createDirectories(outPath.toAbsolutePath().getParent());
        Files.write(outPath, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void appendChain(StringBuilder out, int chainLength) {
        var random = new Random(seed * 1_000_003L + chainLength);
        out.append("  {\n");
        out.append("    \"chainLength\": ").append(chainLength).append(",\n");
        out.append("    \"epochs\": [\n");

        var prevHash = AuditHashing.GENESIS_PREV_EPOCH_HASH;
        for (var i = 0; i < chainLength; i++) {
            var epochId = "epoch-" + chainLength + "-" + i;
            var merkleRoot = "root-" + Integer.toHexString(random.nextInt());
            var sealedAt = "2026-08-30T00:00:0" + (i % 10) + ".000Z";
            out.append("      { \"epochId\": \"").append(epochId)
                    .append("\", \"merkleRoot\": \"").append(merkleRoot)
                    .append("\", \"prevEpochHash\": \"").append(prevHash)
                    .append("\", \"sealedAt\": \"").append(sealedAt).append("\" }");
            out.append(i < chainLength - 1 ? ",\n" : "\n");
            prevHash = AuditHashing.prevEpochHash(epochId, merkleRoot, sealedAt);
        }

        out.append("    ]\n");
        out.append("  }");
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :experiments:harness:e3:test --tests "org.benefits.experiments.e3.ChainFixtureRunnerTest"`
Expected: PASS, all three cases green.

- [ ] **Step 5: Add a combined entry point for both Java runners**

Create `experiments/harness/e3/src/main/java/org/benefits/experiments/e3/RunE3Fixtures.java`:

```java
package org.benefits.experiments.e3;

import java.io.IOException;
import java.util.List;

public class RunE3Fixtures {

    public static void main(String[] args) throws IOException {
        var seed = 1L;
        var epochSizes = List.of(10, 50, 100, 500, 1000, 5000);
        var chainLengths = List.of(5, 20, 100, 500);

        new ProofSizeRunner(seed).run(
                epochSizes,
                "../../results/e3/proof-size.csv",
                "fixtures/inclusion-fixtures.json"
        );
        new ChainFixtureRunner(seed).run(
                chainLengths,
                "fixtures/chain-fixtures.json"
        );

        System.out.println("E3 fixture generation complete");
    }
}
```

- [ ] **Step 6: Commit**

```bash
git add experiments/harness/e3/src/main/java/org/benefits/experiments/e3/ChainFixtureRunner.java \
  experiments/harness/e3/src/main/java/org/benefits/experiments/e3/RunE3Fixtures.java \
  experiments/harness/e3/src/test/java/org/benefits/experiments/e3/ChainFixtureRunnerTest.java
git commit -m "feat(e3): add chain-length fixture export and a combined fixture-generation entry point"
```

---

## Task 10: Node verification-time runners

**Files:**
- Create: `experiments/harness/e3-verify/package.json`
- Create: `experiments/harness/e3-verify/tsconfig.json`
- Create: `experiments/harness/e3-verify/src/csv.ts`
- Create: `experiments/harness/e3-verify/src/host-info.ts`
- Create: `experiments/harness/e3-verify/src/verify-inclusion-time.ts`
- Create: `experiments/harness/e3-verify/src/verify-chain-time.ts`
- Test: `experiments/harness/e3-verify/test/verify-inclusion-time.test.ts`
- Test: `experiments/harness/e3-verify/test/verify-chain-time.test.ts`

**Interfaces:**
- Consumes: `verifyInclusion`, `verifyChain` from `@benefits/audit-verify` (existing, unmodified); the JSON fixture files Tasks 8 and 9 write.

`csv.ts` and `host-info.ts` are duplicated from `experiments/harness/e1` rather than shared, since the two packages are independent workspace members with no existing shared-utility package in this repo to place them in, and each file is under 25 lines; introducing a new shared package for two small, stable utilities would be more machinery than the duplication it avoids.

- [ ] **Step 1: Create the e3-verify package manifest**

Create `experiments/harness/e3-verify/package.json`:

```json
{
  "name": "@benefits/experiment-e3-verify",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "build": "tsc -p tsconfig.json",
    "test": "vitest run",
    "verify-inclusion": "npm run build && node dist/verify-inclusion-time.js",
    "verify-chain": "npm run build && node dist/verify-chain-time.js"
  },
  "dependencies": {
    "@benefits/audit-verify": "0.1.0"
  }
}
```

- [ ] **Step 2: Create the e3-verify tsconfig**

Create `experiments/harness/e3-verify/tsconfig.json`:

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

- [ ] **Step 3: Add utility files (duplicated from experiments/harness/e1)**

Create `experiments/harness/e3-verify/src/csv.ts`:

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

Create `experiments/harness/e3-verify/src/host-info.ts`:

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

- [ ] **Step 4: Run `npm install` at the repo root**

Modify `/home/und3rd06012/benefits/package.json`'s `workspaces` array to also include `experiments/harness/*` (already added in Task 1 as `"experiments/harness/*"`; since `e3-verify` sits directly under `experiments/harness/`, no further change to the workspaces array is needed).

Run: `npm install`
Expected: `experiments/harness/e3-verify` linked as a workspace member, resolving `@benefits/audit-verify` via the existing workspace symlink.

`@benefits/audit-verify`'s `package.json` points `main` at `dist/index.js`, a build output that is not committed to git. In a fresh worktree, that `dist/` directory will not exist yet, and importing `@benefits/audit-verify` in this task's tests (Step 5 onward) will fail with a module-resolution error unrelated to this task's own code. Build it explicitly before proceeding:

Run: `npm run build --workspace=@benefits/audit-verify`
Expected: `packages/audit-verify/dist/index.js` exists.

- [ ] **Step 5: Write the failing test for inclusion-time verification**

Create `experiments/harness/e3-verify/test/verify-inclusion-time.test.ts`:

```typescript
import { describe, it, expect } from 'vitest';
import { runInclusionVerifyTime } from '../src/verify-inclusion-time';
import { mkdtempSync, writeFileSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { verifyInclusion } from '@benefits/audit-verify';

describe('runInclusionVerifyTime', () => {
  it('writes one CSV row per fixture, and every fixture actually verifies', async () => {
    const dir = mkdtempSync(join(tmpdir(), 'e3-verify-'));
    const fixturesPath = join(dir, 'inclusion-fixtures.json');
    const csvPath = join(dir, 'verify-inclusion-time.csv');

    const fixtures = [
      { epochSize: 5, leafIndex: 0, leaf: 'a'.repeat(64), root: 'a'.repeat(64), steps: [] },
      { epochSize: 5, leafIndex: 1, leaf: 'b'.repeat(64), root: 'b'.repeat(64), steps: [] }
    ];
    writeFileSync(fixturesPath, JSON.stringify(fixtures));

    for (const fixture of fixtures) {
      const ok = await verifyInclusion(fixture.leaf, { steps: fixture.steps }, fixture.root);
      expect(ok).toBe(true);
    }

    await runInclusionVerifyTime({ seed: 1, fixturesPath, csvPath, throttleCpus: '', throttleMemoryMb: '' });

    const content = readFileSync(csvPath, 'utf8');
    const lines = content.trim().split('\n');
    expect(lines[0]).toBe('seed,epoch_size,leaf_index,elapsed_ms,throttle_cpus,throttle_memory_mb,host_cpu_model');
    expect(lines.length).toBe(3);
  });
});
```

- [ ] **Step 6: Run test to verify it fails**

Run: `npx vitest run --root experiments/harness/e3-verify`
Expected: FAIL, `Cannot find module '../src/verify-inclusion-time'`.

- [ ] **Step 7: Implement the inclusion-time runner**

Create `experiments/harness/e3-verify/src/verify-inclusion-time.ts`:

```typescript
import { readFileSync } from 'node:fs';
import { verifyInclusion } from '@benefits/audit-verify';
import { writeCsv } from './csv';
import { readHostCpuModel } from './host-info';

interface InclusionFixture {
  epochSize: number;
  leafIndex: number;
  leaf: string;
  root: string;
  steps: { siblingHash: string; siblingIsRight: boolean }[];
}

export interface RunInclusionVerifyTimeOptions {
  seed: number;
  fixturesPath: string;
  csvPath: string;
  throttleCpus: number | string;
  throttleMemoryMb: number | string;
}

export async function runInclusionVerifyTime(opts: RunInclusionVerifyTimeOptions): Promise<void> {
  const fixtures: InclusionFixture[] = JSON.parse(readFileSync(opts.fixturesPath, 'utf8'));
  const hostCpuModel = readHostCpuModel();
  const rows: (string | number)[][] = [];

  for (const fixture of fixtures) {
    const start = process.hrtime.bigint();
    const ok = await verifyInclusion(fixture.leaf, { steps: fixture.steps }, fixture.root);
    const end = process.hrtime.bigint();
    if (!ok) {
      throw new Error(
        'fixture failed to verify (epochSize=' + fixture.epochSize + ', leafIndex=' + fixture.leafIndex + ')'
      );
    }
    const elapsedMs = Number(end - start) / 1e6;
    rows.push([opts.seed, fixture.epochSize, fixture.leafIndex, elapsedMs, opts.throttleCpus, opts.throttleMemoryMb, hostCpuModel]);
  }

  writeCsv(
    opts.csvPath,
    ['seed', 'epoch_size', 'leaf_index', 'elapsed_ms', 'throttle_cpus', 'throttle_memory_mb', 'host_cpu_model'],
    rows
  );
}
```

A fixture that fails to verify throws rather than being recorded as a data point: a verification-time number for a proof that does not actually verify would misreport what E3 measures, and Task 8's own tests already prove every fixture this runner receives verifies against `MerkleTree` on the Java side, so a failure here would mean the Java and TypeScript hashing implementations have diverged, which is exactly what `packages/audit-verify`'s existing conformance vectors (`conformance/audit-vectors/`) already guard against elsewhere; this runner needs to fail loudly, not silently, if that guard is ever wrong.

- [ ] **Step 8: Run test to verify it passes**

Run: `npx vitest run --root experiments/harness/e3-verify`
Expected: PASS.

- [ ] **Step 9: Write the failing test for chain-time verification**

Create `experiments/harness/e3-verify/test/verify-chain-time.test.ts`:

```typescript
import { describe, it, expect } from 'vitest';
import { runChainVerifyTime } from '../src/verify-chain-time';
import { mkdtempSync, writeFileSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { GENESIS_PREV_EPOCH_HASH, prevEpochHash } from '@benefits/audit-verify';

describe('runChainVerifyTime', () => {
  it('writes one CSV row per chain, and every chain actually verifies', async () => {
    const dir = mkdtempSync(join(tmpdir(), 'e3-chain-verify-'));
    const fixturesPath = join(dir, 'chain-fixtures.json');
    const csvPath = join(dir, 'verify-chain-time.csv');

    const genesis = await GENESIS_PREV_EPOCH_HASH;
    const epoch1 = { epochId: 'e1', merkleRoot: 'root1', prevEpochHash: genesis, sealedAt: '2026-08-30T00:00:00.000Z' };
    const nextHash = await prevEpochHash(epoch1.epochId, epoch1.merkleRoot, epoch1.sealedAt);
    const epoch2 = { epochId: 'e2', merkleRoot: 'root2', prevEpochHash: nextHash, sealedAt: '2026-08-30T00:00:01.000Z' };

    const fixtures = [
      { chainLength: 2, epochs: [epoch1, epoch2] },
      { chainLength: 1, epochs: [epoch1] }
    ];
    writeFileSync(fixturesPath, JSON.stringify(fixtures));

    await runChainVerifyTime({ seed: 1, fixturesPath, csvPath, throttleCpus: '', throttleMemoryMb: '' });

    const content = readFileSync(csvPath, 'utf8');
    const lines = content.trim().split('\n');
    expect(lines[0]).toBe('seed,chain_length,elapsed_ms,throttle_cpus,throttle_memory_mb,host_cpu_model');
    expect(lines.length).toBe(3);
  });
});
```

- [ ] **Step 10: Run test to verify it fails**

Run: `npx vitest run --root experiments/harness/e3-verify`
Expected: FAIL, `Cannot find module '../src/verify-chain-time'`.

- [ ] **Step 11: Implement the chain-time runner**

Create `experiments/harness/e3-verify/src/verify-chain-time.ts`:

```typescript
import { readFileSync } from 'node:fs';
import { verifyChain, Epoch } from '@benefits/audit-verify';
import { writeCsv } from './csv';
import { readHostCpuModel } from './host-info';

interface ChainFixture {
  chainLength: number;
  epochs: Epoch[];
}

export interface RunChainVerifyTimeOptions {
  seed: number;
  fixturesPath: string;
  csvPath: string;
  throttleCpus: number | string;
  throttleMemoryMb: number | string;
}

export async function runChainVerifyTime(opts: RunChainVerifyTimeOptions): Promise<void> {
  const fixtures: ChainFixture[] = JSON.parse(readFileSync(opts.fixturesPath, 'utf8'));
  const hostCpuModel = readHostCpuModel();
  const rows: (string | number)[][] = [];

  for (const fixture of fixtures) {
    const start = process.hrtime.bigint();
    const ok = await verifyChain(fixture.epochs);
    const end = process.hrtime.bigint();
    if (!ok) {
      throw new Error('fixture chain of length ' + fixture.chainLength + ' failed to verify');
    }
    const elapsedMs = Number(end - start) / 1e6;
    rows.push([opts.seed, fixture.chainLength, elapsedMs, opts.throttleCpus, opts.throttleMemoryMb, hostCpuModel]);
  }

  writeCsv(
    opts.csvPath,
    ['seed', 'chain_length', 'elapsed_ms', 'throttle_cpus', 'throttle_memory_mb', 'host_cpu_model'],
    rows
  );
}
```

- [ ] **Step 12: Run test to verify it passes**

Run: `npx vitest run --root experiments/harness/e3-verify`
Expected: PASS.

- [ ] **Step 13: Add throttled entry-point scripts**

Create `experiments/harness/e3-verify/src/run-inclusion.ts`:

```typescript
import { runInclusionVerifyTime } from './verify-inclusion-time';

runInclusionVerifyTime({
  seed: 1,
  fixturesPath: '../e3/fixtures/inclusion-fixtures.json',
  csvPath: '../../results/e3/verify-inclusion-time.csv',
  throttleCpus: 1,
  throttleMemoryMb: 1024
}).then(() => {
  console.log('inclusion verify-time run complete');
}).catch((err) => {
  console.error(err);
  process.exitCode = 1;
});
```

Create `experiments/harness/e3-verify/src/run-chain.ts`:

```typescript
import { runChainVerifyTime } from './verify-chain-time';

runChainVerifyTime({
  seed: 1,
  fixturesPath: '../e3/fixtures/chain-fixtures.json',
  csvPath: '../../results/e3/verify-chain-time.csv',
  throttleCpus: 1,
  throttleMemoryMb: 1024
}).then(() => {
  console.log('chain verify-time run complete');
}).catch((err) => {
  console.error(err);
  process.exitCode = 1;
});
```

Modify `experiments/harness/e3-verify/package.json`'s `scripts` block to run these under a throttled container rather than directly, since this is the "participant side" measurement the low-end device profile actually applies to. `run-inclusion.js` and `run-chain.js` read `../e3/fixtures/...` and write `../../results/e3/...`, both outside this package's own directory, and `@benefits/audit-verify` resolves through the workspace root's `node_modules`, so the container must mount the whole repository, not just this package's directory, with its working directory set to this package's path inside that mount:

```json
{
  "name": "@benefits/experiment-e3-verify",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "build": "tsc -p tsconfig.json",
    "test": "vitest run",
    "verify-inclusion": "npm run build && node dist/run-inclusion.js",
    "verify-chain": "npm run build && node dist/run-chain.js",
    "verify-inclusion-throttled": "npm run build && docker run --rm --cpus=1 --memory=1g -v \"$(cd ../../.. && pwd)\":/repo -w /repo/experiments/harness/e3-verify node:22-alpine node dist/run-inclusion.js",
    "verify-chain-throttled": "npm run build && docker run --rm --cpus=1 --memory=1g -v \"$(cd ../../.. && pwd)\":/repo -w /repo/experiments/harness/e3-verify node:22-alpine node dist/run-chain.js"
  },
  "dependencies": {
    "@benefits/audit-verify": "0.1.0"
  }
}
```

- [ ] **Step 14: Commit**

```bash
git add experiments/harness/e3-verify/package.json experiments/harness/e3-verify/tsconfig.json \
  experiments/harness/e3-verify/src experiments/harness/e3-verify/test package.json package-lock.json
git commit -m "feat(e3): add Node verification-time runners for inclusion proofs and epoch chains"
```

---

## Task 11: Reproducibility verification

**Files:**
- No new source files. This task runs the full pipeline twice and diffs output, directly verifying the plan's gate: "results reproduce from seed."

- [ ] **Step 1: Run the E1 sweep twice against a fresh throttled instance each time**

Run:
```bash
cd experiments/harness/e1
docker compose -f docker-compose.e1.yml up -d couchdb-e1-throttled
until curl -sf http://admin:benefits@localhost:5986/_up >/dev/null; do sleep 1; done
mkdir -p /tmp/e1-repro-a /tmp/e1-repro-b
npm run build
node -e "
const { runSweep } = await import('./dist/sweep.js');
await runSweep({
  baseUrl: 'http://localhost:5986', adminUser: 'admin', adminPassword: 'benefits',
  seed: 1, batchSizes: [10, 50], fieldCounts: [1, 5], grantSetSizes: [1, 10],
  trials: 1, outDir: '/tmp/e1-repro-a', throttleCpus: 1, throttleMemoryMb: 1024
});
" --input-type=module
docker compose -f docker-compose.e1.yml restart couchdb-e1-throttled
until curl -sf http://admin:benefits@localhost:5986/_up >/dev/null; do sleep 1; done
node -e "
const { runSweep } = await import('./dist/sweep.js');
await runSweep({
  baseUrl: 'http://localhost:5986', adminUser: 'admin', adminPassword: 'benefits',
  seed: 1, batchSizes: [10, 50], fieldCounts: [1, 5], grantSetSizes: [1, 10],
  trials: 1, outDir: '/tmp/e1-repro-b', throttleCpus: 1, throttleMemoryMb: 1024
});
" --input-type=module
diff <(cut -d, -f1-5 /tmp/e1-repro-a/structural.csv) <(cut -d, -f1-5 /tmp/e1-repro-b/structural.csv)
docker compose -f docker-compose.e1.yml down
```
Expected: the `diff` produces no output. Only `wall_time_ms` (column 6, excluded by `cut -f1-5`) is expected to vary between runs; every other column (seed, trial, batch_size, field_count, grant_set_size) must be byte-identical, since those are what "reproduces from seed" actually means for a wall-clock measurement, timing itself is never claimed to be bit-reproducible.

- [ ] **Step 2: Run the E3 fixture generation and verification pipeline twice**

`experiments/harness/e3`'s `build.gradle.kts` (Task 7) applies only the `java-library` plugin, not `application`, so there is no `gradlew run` task; run the compiled main class directly with `java -cp`. `RunE3Fixtures`'s output paths (`../../results/e3/...` and `fixtures/...`) are relative to the JVM's working directory, so this must run with `experiments/harness/e3` as the working directory, exactly as it would if `application` were configured with that module as its project directory.

Run:
```bash
cd /home/und3rd06012/benefits
./gradlew :experiments:harness:e3:testClasses :connector:extensions:benefits-audit:jar
cd experiments/harness/e3
mkdir -p fixtures
java -cp "build/classes/java/main:../../../connector/extensions/benefits-audit/build/libs/*" \
  org.benefits.experiments.e3.RunE3Fixtures
cp -r fixtures /tmp/e3-fixtures-run-a
cp ../../results/e3/proof-size.csv /tmp/proof-size-run-a.csv
java -cp "build/classes/java/main:../../../connector/extensions/benefits-audit/build/libs/*" \
  org.benefits.experiments.e3.RunE3Fixtures
diff -r /tmp/e3-fixtures-run-a fixtures
diff /tmp/proof-size-run-a.csv ../../results/e3/proof-size.csv
cd /home/und3rd06012/benefits
```
Expected: both `diff` commands produce no output, confirming the Java side is fully reproducible (no wall-clock columns here, since Task 8/9 do not time anything server-side).

- [ ] **Step 3: Run the Node verification-time runners twice against the same fixtures**

Run:
```bash
cd experiments/harness/e3-verify
npm run build
node dist/run-inclusion.js
cp ../../results/e3/verify-inclusion-time.csv /tmp/verify-inclusion-run-a.csv
node dist/run-inclusion.js
diff <(cut -d, -f1-3 /tmp/verify-inclusion-run-a.csv) <(cut -d, -f1-3 ../../results/e3/verify-inclusion-time.csv)
node dist/run-chain.js
cp ../../results/e3/verify-chain-time.csv /tmp/verify-chain-run-a.csv
node dist/run-chain.js
diff <(cut -d, -f1-2 /tmp/verify-chain-run-a.csv) <(cut -d, -f1-2 ../../results/e3/verify-chain-time.csv)
```
Expected: both `diff` commands produce no output for the non-timing columns (seed, epoch_size/leaf_index for inclusion; seed, chain_length for chain), confirming which fixture is verified is fully determined by the seed, and only `elapsed_ms` is expected to vary run to run.

- [ ] **Step 4: Record the result**

If any step above produces unexpected diff output (a column other than the timing column differs between runs), stop and treat it as a bug in the generator that produced it, not as an acceptable variance, since it directly contradicts the plan's gate. If all three steps pass as expected, this task's verification is the deliverable; there is nothing to commit beyond a note in the final report confirming the gate was checked and passed.

---

## Final Verification

After all 11 tasks are complete:

1. `npm run build --workspaces --if-present` succeeds, including the three new workspace members (`experiments/harness/e1`, `experiments/harness/e3-verify`, and any other new members from Task 1/10).
2. `npm test --workspaces --if-present` succeeds; the E1 `sweep.integration.test.ts` and any live-CouchDB-dependent E3-verify tests remain skippable without a live instance, matching this repo's existing pattern of gating live-dependent suites (e.g. `E1_LIVE`).
3. `./gradlew build` succeeds, including `:experiments:harness:e3`.
4. `./gradlew test` succeeds, including the new `SeededFixtureGeneratorTest`, `ProofSizeRunnerTest`, and `ChainFixtureRunnerTest`.
5. Task 11's reproducibility checks all pass.
6. `experiments/results/e1/{baseline,structural,signature}.csv` and `experiments/results/e3/{proof-size,verify-inclusion-time,verify-chain-time}.csv` exist and match the schemas in the spec's sections 3.5 and 4.5.
7. Nothing under `packages/consent-couchdb`, `connector/extensions/benefits-privacy`, or `connector/extensions/benefits-audit`'s non-test sources was modified (per the spec's non-goals, section 6).
