# BENEFITS Interfaces (Phase 6) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the three real BENEFITS client interfaces (participant-web, steward-portal, participant-mobile/Flutter) and the backend surface they require: participant-identity write enforcement, a real steward review/co-sign/veto/appeal workflow, real release-request creation, and real audit-viewer authentication.

**Architecture:** Extends the existing two-tier split rather than adding a gateway: participant-web and participant-mobile write straight to CouchDB (Tier 1, enforced by an extended `validate_doc_update` S1 gate); steward-portal talks to new JAX-RS resources on the EDC connector (Tier 3). Both participants and stewards authenticate as real CouchDB users, distinguished by role.

**Tech Stack:** TypeScript/Vitest/esbuild (existing), Java 17/Gradle/JUnit/AssertJ (existing), React + Vite for the two web apps (new), Flutter/Dart for mobile (new), CouchDB 3.5.2 (existing, pinned by image digest).

**Spec:** docs/superpowers/specs/2026-09-01-benefits-interfaces-design.md

## Global Constraints

- No unified API gateway in front of CouchDB — participant-web and participant-mobile write straight to CouchDB; steward-portal talks only to the connector (spec §2).
- Single shared CouchDB database, not one database per participant (spec §3).
- CouchDB user roles are the authorization boundary: participants are provisioned with `"roles": []`, stewards with `"roles": ["steward"]`. `StewardResource` requires the `"steward"` role from `/_session`; `AuditResource` requires only identity match, no role.
- CouchDB users with the `_admin` role are exempt from the new participant-identity write check — this is required, not optional: every phase 4/5 admin-authenticated test and every existing `tools/*.sh` script authenticates as `admin:benefits` and would otherwise break.
- The participant-identity write check lives entirely in `packages/consent-couchdb/src/build-design-doc.ts`. It is NOT a `consent-core` change and does NOT touch conformance vectors or `ConsentEvaluator.java` — `userCtx` has no equivalent in the pure `evaluateRecord` conformance surface, which stays tri-consumer (TS/Java/CouchDB) exactly as phase 5 left it.
- New CouchDB-backed stores (`CouchDbStewardAuthorizationStore`, `CouchDbReleaseRequestStore`, `CouchDbReleaseArtifactStore`, `CouchDbBudgetAccountant`) reuse the existing four settings keys — `benefits.couchdb.url`, `benefits.couchdb.database` (each appending its own suffix, e.g. `-steward`, `-requests`, `-artifacts`, `-budget`), `benefits.couchdb.user`, `benefits.couchdb.password` — matching `CouchDbAuditStore`'s constructor shape exactly: `(String baseUrl, String database, String user, String password, ObjectMapper mapper)`.
- Every CouchDB Mango selector is built via `ObjectMapper`, never string interpolation (existing `CouchDbAuditStore`/`CouchDbContributionSource` convention — a past defect class).
- Every CouchDB I/O exception becomes `EdcException`; an `InterruptedException` re-sets the thread's interrupt flag before rethrowing as `EdcException` — no exception to this pattern.
- A new Gradle module needs an `include(":connector:extensions:<name>")` line in root `settings.gradle.kts` AND an `implementation(project(":connector:extensions:<name>"))` line in `connector/runtime/build.gradle.kts`, or it never loads at runtime.
- A new extension registering an HTTP resource needs `api(libs.edc.web.spi)` in its own `build.gradle.kts` (see `benefits-audit/build.gradle.kts`) and must register its own `PortMapping` via `PortMappingRegistry` before `webService.registerResource(...)` — a brand-new context name has no auto-registered port mapping, confirmed by `BenefitsAuditExtension`'s own doc comment.
- Root `package.json`'s `"workspaces"` field is `["packages/*"]` only; it needs `"apps/*"` added for the two new web apps.
- The root `tsconfig.json`'s ES5 target/lib exists solely because CouchDB's query-server source parser rejects object spread — it applies only to code bundled into the CouchDB design document (`consent-core`'s `evaluate.ts` path). Any browser-targeted package or app (`consent-web-client`, `apps/participant-web`, `apps/steward-portal`) gets its own separate `tsconfig.json`, not an extension of the root one.
- New TS packages/apps pin `typescript@5.7.2` and `vitest@2.1.8`, matching root's hoisted versions. New npm dependencies are verified against the real npm registry before being added (phase 5 caught a nonexistent `@types/pouchdb-adapter-memory@6.1.7` this way — the real version is `6.1.6`).
- No emojis and no em-dashes in any code, comments, commit messages, or UI copy this plan produces (standing project convention).
- `EventSubscriber.on` is a generic method (`<E extends Event> void on(EventEnvelope<E>)`) and cannot be implemented by a lambda — this plan does not touch event subscription, but any new code that does must follow `BenefitsAuditEventListener`'s existing anonymous-class pattern, not a lambda.

---

### Task 1: Participant-identity write enforcement in the S1 gate

**Files:**
- Modify: `packages/consent-couchdb/src/build-design-doc.ts`
- Create: `packages/consent-couchdb/test/identity.integration.test.ts`
- Create: `packages/consent-couchdb/test/couchdb-users.ts` (shared test helper)

**Interfaces:**
- Consumes: nothing from an earlier task (first task).
- Produces: `build-design-doc.ts`'s `buildDesignDoc()` return value is unchanged in shape (`{ _id, validate_doc_update }`); its generated `validate_doc_update` string now rejects a write when the authenticated CouchDB user does not own the document. Later tasks (11, 13) write client code whose writes must go through this check under real, non-admin, per-participant CouchDB users.

This is Gate 1 from the spec. Every simulated device in phase 5 wrote under shared `admin:benefits` credentials; nothing today stops one participant's credentials writing another's document. This task closes that, in the one place `userCtx` (CouchDB's authenticated-caller context) is actually available: the generated `validate_doc_update` function itself, not `consent-core`'s pure `evaluateRecord`.

- [ ] **Step 1: Read the current `build-design-doc.ts` for the exact insertion point**

Current file (confirmed by grounding research, 41 lines):

```typescript
import { buildSync } from 'esbuild';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));

export interface DesignDoc {
  _id: string;
  validate_doc_update: string;
}

export function buildDesignDoc(): DesignDoc {
  const entry = join(here, '../../consent-core/src/evaluate.ts');

  const result = buildSync({
    entryPoints: [entry],
    bundle: true,
    write: false,
    format: 'iife',
    globalName: 'ConsentCore',
    target: 'es5',
    minify: false,
    legalComments: 'none'
  });

  const bundle = result.outputFiles[0].text;

  const validate = [
    'function(newDoc, oldDoc, userCtx, secObj) {',
    '  ' + bundle.split('\n').join('\n  '),
    '  if (newDoc._deleted) { return; }',
    '  var decision = ConsentCore.evaluateRecord(newDoc, new Date().toISOString());',
    '  if (!decision.allowed) {',
    '    throw({ forbidden: decision.reason });',
    '  }',
    '}'
  ].join('\n');

  return { _id: '_design/consent', validate_doc_update: validate };
}
```

`userCtx` is already a parameter CouchDB always passes; it is currently unused. `userCtx.roles` is an array that contains `"_admin"` for a cluster admin. `newDoc.participantRef` exists on both `ConsentAssertion`-bearing docs and `ContributionRecord` docs (both have a top-level `participantRef` field per `consent-core/src/types.ts`).

The identity check must run in two places relative to the existing `if (newDoc._deleted) { return; }` line:
- **On a real document write** (`newDoc._deleted` is falsy): reject unless `userCtx.name === newDoc.participantRef`, exempting `_admin`.
- **On a tombstone write** (`newDoc._deleted` is true, e.g. from `remote.remove()` during conflict resolution, Task 11): `newDoc.participantRef` is not present on a deletion stub. The document being deleted is available as `oldDoc`. Reject unless `userCtx.name === oldDoc.participantRef`, exempting `_admin`. Getting this backwards either 403s a participant's own legitimate conflict-resolution deletes (checking after the existing `_deleted` early return would skip the check on deletes entirely, which is the *other* direction of the same bug: it would let any provisioned participant delete anyone's document) or blocks them entirely (checking `newDoc.participantRef` on a doc that doesn't have one). Both directions are covered by tests below.

- [ ] **Step 2: Write the failing tests first**

Create `packages/consent-couchdb/test/couchdb-users.ts`:

```typescript
const BASE = 'http://localhost:5984';
const ADMIN_AUTH = 'Basic ' + Buffer.from('admin:benefits').toString('base64');

export function basicAuth(name: string, password: string): string {
  return 'Basic ' + Buffer.from(`${name}:${password}`).toString('base64');
}

export async function ensureCouchDbUser(name: string, password: string, roles: string[] = []): Promise<void> {
  const res = await fetch(`${BASE}/_users/org.couchdb.user:${name}`, {
    method: 'PUT',
    headers: { authorization: ADMIN_AUTH, 'content-type': 'application/json' },
    body: JSON.stringify({ name, password, roles, type: 'user' })
  });
  if (res.status !== 201 && res.status !== 409) {
    throw new Error(`failed to provision CouchDB user ${name}: ${res.status}`);
  }
}
```

`409` is accepted because a prior test run may have already created the user; CouchDB user documents are not deleted between runs (only the test database is, in each integration test file's `beforeAll`/`afterAll`).

Create `packages/consent-couchdb/test/identity.integration.test.ts`:

```typescript
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { buildDesignDoc } from '../src/build-design-doc';
import { basicAuth, ensureCouchDbUser } from './couchdb-users';

const BASE = 'http://localhost:5984';
const DB = 'benefits-identity-test';
const ADMIN_AUTH = 'Basic ' + Buffer.from('admin:benefits').toString('base64');

let couchDbReachable = false;
try {
  await fetch(BASE);
  couchDbReachable = true;
} catch {
  couchDbReachable = false;
}

async function req(auth: string, method: string, path: string, body?: unknown) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: { 'content-type': 'application/json', authorization: auth },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  return { status: res.status, body: await res.json().catch(() => ({})) };
}

function contribution(participantRef: string, id: string) {
  return {
    _id: id,
    type: 'contribution',
    participantRef,
    siteId: 'athens',
    collectedAt: '2026-09-01T00:00:00.000Z',
    channel: 'app',
    declaredPurpose: 'service-improvement',
    declaredRecipientClass: 'statistics',
    fields: { 'service.satisfaction': 4 },
    consent: {
      participantRef,
      assertedAt: '2026-08-01T00:00:00.000Z',
      grants: [{
        field: 'service.satisfaction', purpose: 'service-improvement', recipientClass: 'statistics',
        grantedAt: '2026-08-01T00:00:00.000Z', expiresAt: null, revokedAt: null
      }]
    }
  };
}

describe.skipIf(!couchDbReachable)('participant-identity write enforcement', () => {
  beforeAll(async () => {
    await req(ADMIN_AUTH, 'DELETE', `/${DB}`);
    await req(ADMIN_AUTH, 'PUT', `/${DB}`);
    const dd = buildDesignDoc();
    await req(ADMIN_AUTH, 'PUT', `/${DB}/${dd._id}`, dd);
    await ensureCouchDbUser('p-identity-a', 'pw-a', []);
    await ensureCouchDbUser('p-identity-b', 'pw-b', []);
  }, 30000);

  afterAll(async () => {
    await req(ADMIN_AUTH, 'DELETE', `/${DB}`);
  });

  it('rejects a write whose authenticated user does not match participantRef', async () => {
    const auth = basicAuth('p-identity-a', 'pw-a');
    const res = await req(auth, 'PUT', `/${DB}/doc-1`, contribution('p-identity-b', 'doc-1'));
    expect(res.status).toBe(403);
  });

  it('accepts a write whose authenticated user matches participantRef', async () => {
    const auth = basicAuth('p-identity-a', 'pw-a');
    const res = await req(auth, 'PUT', `/${DB}/doc-2`, contribution('p-identity-a', 'doc-2'));
    expect(res.status).toBe(201);
  });

  it('admin-authenticated writes are exempt from the identity check', async () => {
    const res = await req(ADMIN_AUTH, 'PUT', `/${DB}/doc-3`, contribution('someone-else-entirely', 'doc-3'));
    expect(res.status).toBe(201);
  });

  it('rejects deleting a document owned by a different participant', async () => {
    const authA = basicAuth('p-identity-a', 'pw-a');
    const authB = basicAuth('p-identity-b', 'pw-b');
    const created = await req(authA, 'PUT', `/${DB}/doc-4`, contribution('p-identity-a', 'doc-4'));
    expect(created.status).toBe(201);
    const rev = (created.body as { rev: string }).rev;
    const res = await req(authB, 'DELETE', `/${DB}/doc-4?rev=${rev}`);
    expect(res.status).toBe(403);
  });

  it('accepts deleting a document owned by the authenticated participant', async () => {
    const authA = basicAuth('p-identity-a', 'pw-a');
    const created = await req(authA, 'PUT', `/${DB}/doc-5`, contribution('p-identity-a', 'doc-5'));
    expect(created.status).toBe(201);
    const rev = (created.body as { rev: string }).rev;
    const res = await req(authA, 'DELETE', `/${DB}/doc-5?rev=${rev}`);
    expect(res.status).toBe(200);
  });
});
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cd packages/consent-couchdb && npx vitest run test/identity.integration.test.ts`
Expected: the deletion tests fail differently from the write tests. Write tests fail because nothing rejects the mismatched write (`403` expected, `201` actual). The "rejects deleting" test needs CouchDB reachable; if CouchDB is not running, the whole suite reports skipped (confirm this happens rather than a false pass, per the `describe.skipIf` top-level-`await` pattern already established in phase 5).

- [ ] **Step 4: Implement the check**

Modify `packages/consent-couchdb/src/build-design-doc.ts`, replacing the `validate` array:

```typescript
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
    '  var decision = ConsentCore.evaluateRecord(newDoc, new Date().toISOString());',
    '  if (!decision.allowed) {',
    '    throw({ forbidden: decision.reason });',
    '  }',
    '}'
  ].join('\n');
```

`oldDoc` on a fresh document's first write is `null`, but that path never reaches this branch (a first write has `newDoc._deleted` falsy, not truthy).

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd packages/consent-couchdb && npx vitest run test/identity.integration.test.ts`
Expected: all 5 tests PASS (or all skipped if CouchDB is unreachable in this environment — start it per README before re-running: `docker compose -f deploy/docker-compose.yml up -d`).

- [ ] **Step 6: Run the full existing consent-couchdb suite to confirm no regression**

Run: `cd packages/consent-couchdb && npx vitest run`
Expected: `design-doc.test.ts` and `couchdb.integration.test.ts` (both admin-authenticated) still PASS unchanged — this is the direct regression check for the `_admin` exemption.

- [ ] **Step 7: Commit**

```bash
git add packages/consent-couchdb/src/build-design-doc.ts packages/consent-couchdb/test/identity.integration.test.ts packages/consent-couchdb/test/couchdb-users.ts
git commit -m "feat(consent-couchdb): enforce participant-identity on writes and deletes"
```

### Task 2: Steward domain model (review, veto, appeal) and atomic append

**Files:**
- Modify: `connector/extensions/benefits-policy/src/main/java/org/benefits/policy/StewardAuthorization.java`
- Modify: `connector/extensions/benefits-policy/src/main/java/org/benefits/policy/StewardAuthorizationStore.java`
- Modify: `connector/extensions/benefits-policy/src/main/java/org/benefits/policy/InMemoryStewardAuthorizationStore.java`
- Create: `connector/extensions/benefits-policy/src/main/java/org/benefits/policy/StewardReview.java`
- Create: `connector/extensions/benefits-policy/src/main/java/org/benefits/policy/StewardVeto.java`
- Create: `connector/extensions/benefits-policy/src/main/java/org/benefits/policy/StewardAppeal.java`
- Modify: `connector/extensions/benefits-policy/src/test/java/org/benefits/policy/InMemoryStewardAuthorizationStoreTest.java`
- Modify: `connector/extensions/benefits-policy/src/test/java/org/benefits/policy/StewardQuorumFunctionTest.java`
- Modify: `tools/run-case.sh`

**Interfaces:**
- Consumes: nothing from Task 1 (independent subsystem).
- Produces: `StewardAuthorization(releaseRequestId, datasetHash, signatures, reviews, vetoes, appeals, issuedAt)` — a 7-arg record. `StewardAuthorizationStore.appendReview/appendSignature/appendVeto/appendAppeal(...)`, each returning the updated `StewardAuthorization`. Task 3 (`CouchDbStewardAuthorizationStore`) implements the same interface. Task 9 (`StewardResource`) calls only these four append methods, never `save()`, once handling live HTTP requests.

Spec §5.1 requires review, co-sign, veto, and appeal to be four distinct, queryable states — a steward who scores the rubric without signing must be representable. `StewardSignature` (the co-sign record) stays unchanged so `StewardQuorumFunction`'s existing, already-tested contract (`distinctStewardCount()` over `signatures` only) is untouched.

`StewardAuthorizationStore.save()`'s own doc comment (unchanged, still true) says replace-semantics require the caller to be the sole writer; this task adds the atomic append operation that comment says is required before any concurrent write path exists. `save()` stays, for the one caller that is genuinely a sole writer: `BenefitsPolicyExtension.seed()` (test/demo seeding, run once at startup, never concurrent).

- [ ] **Step 1: Write the new sibling records**

`StewardReview.java`:
```java
package org.benefits.policy;

import java.time.Instant;

public record StewardReview(String stewardId, int rubricScore, String notes, Instant reviewedAt) {
}
```

`StewardVeto.java`:
```java
package org.benefits.policy;

import java.time.Instant;

public record StewardVeto(String stewardId, String reason, Instant vetoedAt) {
}
```

`StewardAppeal.java`:
```java
package org.benefits.policy;

import java.time.Instant;

/**
 * Contests a specific veto, named by the vetoing steward's id. A release request can
 * carry more than one veto (from different stewards); an appeal names which one it
 * contests rather than appealing "the" veto.
 */
public record StewardAppeal(String appellantId, String reason, String vetoStewardId, Instant appealedAt) {
}
```

- [ ] **Step 2: Extend `StewardAuthorization`**

```java
package org.benefits.policy;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

public record StewardAuthorization(
        String releaseRequestId,
        String datasetHash,
        List<StewardSignature> signatures,
        List<StewardReview> reviews,
        List<StewardVeto> vetoes,
        List<StewardAppeal> appeals,
        Instant issuedAt) {

    public StewardAuthorization {
        signatures = List.copyOf(signatures);
        reviews = List.copyOf(reviews);
        vetoes = List.copyOf(vetoes);
        appeals = List.copyOf(appeals);
    }

    public int distinctStewardCount() {
        return signatures.stream()
                .map(StewardSignature::stewardId)
                .collect(Collectors.toSet())
                .size();
    }
}
```

`StewardSignature.java` is not modified.

- [ ] **Step 3: Update `InMemoryStewardAuthorizationStoreTest` for the new constructor shape, and run it to confirm it fails to compile first**

Run: `./gradlew :connector:extensions:benefits-policy:compileTestJava`
Expected: FAIL — `StewardAuthorization` no longer has a 4-arg constructor.

Update every `new StewardAuthorization(...)` call site in this file to the 7-arg form, passing `List.of()` for the three new lists:

```java
    @Test
    void mutatingCallerListAfterConstructionDoesNotChangeDistinctStewardCount() {
        var mutableSignatures = new java.util.ArrayList<StewardSignature>();
        mutableSignatures.add(new StewardSignature("s-1", "sig-s-1", Instant.parse("2026-08-30T12:00:00Z")));
        var auth = new StewardAuthorization("rr-4", "hash-abc", mutableSignatures, List.of(), List.of(), List.of(),
                Instant.parse("2026-08-30T12:00:00Z"));

        mutableSignatures.add(new StewardSignature("s-2", "sig-s-2", Instant.parse("2026-08-30T12:00:00Z")));

        assertThat(auth.distinctStewardCount()).isEqualTo(1);
    }

    private StewardAuthorization authorization(String releaseRequestId, String... stewardIds) {
        var signatures = java.util.Arrays.stream(stewardIds)
                .map(id -> new StewardSignature(id, "sig-" + id, Instant.parse("2026-08-30T12:00:00Z")))
                .toList();
        return new StewardAuthorization(releaseRequestId, "hash-abc", List.copyOf(signatures), List.of(), List.of(), List.of(),
                Instant.parse("2026-08-30T12:00:00Z"));
    }
```

(Only the two call sites above construct a `StewardAuthorization` directly in this file; the other three tests call `authorization(...)`.)

Similarly update `StewardQuorumFunctionTest`'s own authorization-construction helper (it seeds via `store.save(new StewardAuthorization(...))` in its `@BeforeEach`/test bodies) to the 7-arg form with `List.of()` for reviews/vetoes/appeals — read the file first to find the exact call sites, since the excerpt available to this plan only shows the file's first 40 lines.

- [ ] **Step 4: Write the new append tests in `InMemoryStewardAuthorizationStoreTest`**

Add:
```java
    @Test
    void appendSignatureCreatesAnAuthorizationWhenNoneExists() {
        var review = new StewardReview("s-1", 4, "looks fine", Instant.parse("2026-08-30T12:00:00Z"));
        var updated = store.appendReview("rr-5", "hash-abc", review);
        assertThat(updated.reviews()).containsExactly(review);
        assertThat(updated.signatures()).isEmpty();
        assertThat(store.findByReleaseRequestId("rr-5")).isEqualTo(updated);
    }

    @Test
    void appendSignaturePreservesExistingReviewsAndVetoes() {
        var review = new StewardReview("s-1", 4, "looks fine", Instant.parse("2026-08-30T12:00:00Z"));
        store.appendReview("rr-6", "hash-abc", review);
        var signature = new StewardSignature("s-1", "sig-s-1", Instant.parse("2026-08-30T12:01:00Z"));
        var updated = store.appendSignature("rr-6", "hash-abc", signature);
        assertThat(updated.reviews()).containsExactly(review);
        assertThat(updated.signatures()).containsExactly(signature);
    }

    @Test
    void appendVetoDoesNotCountTowardDistinctStewardCount() {
        var veto = new StewardVeto("s-9", "field set too broad", Instant.parse("2026-08-30T12:00:00Z"));
        var updated = store.appendVeto("rr-7", "hash-abc", veto);
        assertThat(updated.vetoes()).containsExactly(veto);
        assertThat(updated.distinctStewardCount()).isZero();
    }

    @Test
    void appendAppealFailsWhenNoAuthorizationExists() {
        var appeal = new StewardAppeal("p-1", "veto was mistaken", "s-9", Instant.parse("2026-08-30T13:00:00Z"));
        assertThatThrownBy(() -> store.appendAppeal("rr-missing", appeal))
                .isInstanceOf(org.eclipse.edc.spi.EdcException.class);
    }

    @Test
    void appendAppealFailsWhenTheNamedVetoDoesNotExist() {
        store.appendVeto("rr-8", "hash-abc", new StewardVeto("s-9", "reason", Instant.parse("2026-08-30T12:00:00Z")));
        var appeal = new StewardAppeal("p-1", "veto was mistaken", "s-not-the-vetoer", Instant.parse("2026-08-30T13:00:00Z"));
        assertThatThrownBy(() -> store.appendAppeal("rr-8", appeal))
                .isInstanceOf(org.eclipse.edc.spi.EdcException.class);
    }

    @Test
    void appendAppealSucceedsAgainstAnExistingVeto() {
        store.appendVeto("rr-9", "hash-abc", new StewardVeto("s-9", "reason", Instant.parse("2026-08-30T12:00:00Z")));
        var appeal = new StewardAppeal("p-1", "veto was mistaken", "s-9", Instant.parse("2026-08-30T13:00:00Z"));
        var updated = store.appendAppeal("rr-9", appeal);
        assertThat(updated.appeals()).containsExactly(appeal);
    }
```

Add `import static org.assertj.core.api.Assertions.assertThatThrownBy;` to this file's imports.

- [ ] **Step 5: Run to verify these fail**

Run: `./gradlew :connector:extensions:benefits-policy:test --tests InMemoryStewardAuthorizationStoreTest`
Expected: compile failure (the four `append*` methods do not exist yet on the interface).

- [ ] **Step 6: Extend the interface and implementation**

`StewardAuthorizationStore.java`:
```java
package org.benefits.policy;

import org.jetbrains.annotations.Nullable;

public interface StewardAuthorizationStore {

    @Nullable
    StewardAuthorization findByReleaseRequestId(String releaseRequestId);

    /**
     * Stores the given authorization, replacing any existing authorization for the
     * same release request id wholesale rather than merging into it. The caller must
     * be the sole writer for a given release request id when calling this method;
     * the only caller in this codebase is BenefitsPolicyExtension.seed(), which runs
     * once at startup, never concurrently. Live HTTP request handlers must use the
     * append* methods below instead, which are atomic per release request id.
     */
    void save(StewardAuthorization authorization);

    /**
     * Atomically appends a review, creating a fresh authorization (with the given
     * datasetHash) if none exists yet for this release request id. If an authorization
     * already exists, the given datasetHash is ignored in favour of the one already
     * stored -- datasetHash is fixed at first creation, not updated on later appends.
     */
    StewardAuthorization appendReview(String releaseRequestId, String datasetHash, StewardReview review);

    StewardAuthorization appendSignature(String releaseRequestId, String datasetHash, StewardSignature signature);

    StewardAuthorization appendVeto(String releaseRequestId, String datasetHash, StewardVeto veto);

    /**
     * Appends an appeal contesting an existing veto. Throws EdcException if no
     * authorization exists yet for this release request id, or if no veto by
     * appeal.vetoStewardId() is recorded against it -- an appeal cannot create the
     * veto it contests.
     */
    StewardAuthorization appendAppeal(String releaseRequestId, StewardAppeal appeal);
}
```

`InMemoryStewardAuthorizationStore.java`:
```java
package org.benefits.policy;

import org.eclipse.edc.spi.EdcException;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryStewardAuthorizationStore implements StewardAuthorizationStore {

    private final Map<String, StewardAuthorization> byReleaseRequest = new ConcurrentHashMap<>();

    @Override
    @Nullable
    public StewardAuthorization findByReleaseRequestId(String releaseRequestId) {
        return byReleaseRequest.get(releaseRequestId);
    }

    @Override
    public void save(StewardAuthorization authorization) {
        byReleaseRequest.put(authorization.releaseRequestId(), authorization);
    }

    @Override
    public StewardAuthorization appendReview(String releaseRequestId, String datasetHash, StewardReview review) {
        return byReleaseRequest.compute(releaseRequestId, (id, existing) -> {
            var base = existing != null ? existing : empty(releaseRequestId, datasetHash);
            return new StewardAuthorization(base.releaseRequestId(), base.datasetHash(), base.signatures(),
                    append(base.reviews(), review), base.vetoes(), base.appeals(), base.issuedAt());
        });
    }

    @Override
    public StewardAuthorization appendSignature(String releaseRequestId, String datasetHash, StewardSignature signature) {
        return byReleaseRequest.compute(releaseRequestId, (id, existing) -> {
            var base = existing != null ? existing : empty(releaseRequestId, datasetHash);
            return new StewardAuthorization(base.releaseRequestId(), base.datasetHash(), append(base.signatures(), signature),
                    base.reviews(), base.vetoes(), base.appeals(), base.issuedAt());
        });
    }

    @Override
    public StewardAuthorization appendVeto(String releaseRequestId, String datasetHash, StewardVeto veto) {
        return byReleaseRequest.compute(releaseRequestId, (id, existing) -> {
            var base = existing != null ? existing : empty(releaseRequestId, datasetHash);
            return new StewardAuthorization(base.releaseRequestId(), base.datasetHash(), base.signatures(),
                    base.reviews(), append(base.vetoes(), veto), base.appeals(), base.issuedAt());
        });
    }

    @Override
    public StewardAuthorization appendAppeal(String releaseRequestId, StewardAppeal appeal) {
        var existing = byReleaseRequest.get(releaseRequestId);
        if (existing == null) {
            throw new EdcException("cannot appeal a veto for release request with no steward authorization: " + releaseRequestId);
        }
        var vetoExists = existing.vetoes().stream().anyMatch(v -> v.stewardId().equals(appeal.vetoStewardId()));
        if (!vetoExists) {
            throw new EdcException("no veto by " + appeal.vetoStewardId() + " to appeal for release request " + releaseRequestId);
        }
        return byReleaseRequest.compute(releaseRequestId, (id, current) -> new StewardAuthorization(
                current.releaseRequestId(), current.datasetHash(), current.signatures(),
                current.reviews(), current.vetoes(), append(current.appeals(), appeal), current.issuedAt()));
    }

    private static StewardAuthorization empty(String releaseRequestId, String datasetHash) {
        return new StewardAuthorization(releaseRequestId, datasetHash, List.of(), List.of(), List.of(), List.of(), Instant.now());
    }

    private static <T> List<T> append(List<T> list, T item) {
        var copy = new ArrayList<>(list);
        copy.add(item);
        return List.copyOf(copy);
    }
}
```

`appendAppeal`'s existence check happens outside `compute()` deliberately: the veto-existence validation must reject before any mutation, and `Map.compute()`'s remapping function cannot itself decide to leave the map unchanged by throwing without also being re-entered on retry in some `Map` implementations -- reading first and validating, then computing only the append, keeps the failure path exception-only-no-side-effect. A veto appended concurrently between the check and the `compute()` call is not a race this in-memory store needs to close: appeals are user-initiated, rare, and not on the phase 6 gate's tested concurrency path (only concurrent co-signs, Gate 2, are).

- [ ] **Step 7: Run to verify all tests pass**

Run: `./gradlew :connector:extensions:benefits-policy:test --tests InMemoryStewardAuthorizationStoreTest --tests StewardQuorumFunctionTest`
Expected: PASS.

- [ ] **Step 8: Update `tools/run-case.sh`'s seed generator for the new required fields**

Modify the python heredoc that writes `seed.json`:

```python
import json, sys
case, out = sys.argv[1], sys.argv[2]
stewards = sys.argv[3:]
json.dump([{
    "releaseRequestId": "rr-" + case,
    "datasetHash": "hash-" + case,
    "issuedAt": "2026-08-30T12:00:00Z",
    "signatures": [
        {"stewardId": s, "signature": "sig-" + s + "-" + str(i), "signedAt": "2026-08-30T12:00:00Z"}
        for i, s in enumerate(stewards)
    ],
    "reviews": [],
    "vetoes": [],
    "appeals": []
}], open(out, "w"))
```

- [ ] **Step 9: Run the existing shell probe cases to confirm no regression**

Run: `./tools/run-case.sh quorum-met` (requires CouchDB and built jars per README; if unavailable in this environment, note that in the report rather than skip silently -- this script's JSON shape change is a real regression risk for the whole probe suite).
Expected: the script completes the same way it did before this task (negotiation succeeds), confirming Jackson deserializes the new 7-field seed JSON into `StewardAuthorization` without error.

- [ ] **Step 10: Commit**

```bash
git add connector/extensions/benefits-policy/src/main/java/org/benefits/policy/StewardAuthorization.java \
        connector/extensions/benefits-policy/src/main/java/org/benefits/policy/StewardAuthorizationStore.java \
        connector/extensions/benefits-policy/src/main/java/org/benefits/policy/InMemoryStewardAuthorizationStore.java \
        connector/extensions/benefits-policy/src/main/java/org/benefits/policy/StewardReview.java \
        connector/extensions/benefits-policy/src/main/java/org/benefits/policy/StewardVeto.java \
        connector/extensions/benefits-policy/src/main/java/org/benefits/policy/StewardAppeal.java \
        connector/extensions/benefits-policy/src/test/java/org/benefits/policy/InMemoryStewardAuthorizationStoreTest.java \
        connector/extensions/benefits-policy/src/test/java/org/benefits/policy/StewardQuorumFunctionTest.java \
        tools/run-case.sh
git commit -m "feat(benefits-policy): steward review/veto/appeal domain model with atomic append"
```

### Task 3: CouchDbStewardAuthorizationStore (persistence + concurrency)

**Files:**
- Create: `connector/extensions/benefits-policy/src/main/java/org/benefits/policy/CouchDbStewardAuthorizationStore.java`
- Create: `connector/extensions/benefits-policy/src/test/java/org/benefits/policy/CouchDbStewardAuthorizationStoreIntegrationTest.java`
- Modify: `connector/extensions/benefits-policy/src/main/java/org/benefits/policy/BenefitsPolicyExtension.java`

**Interfaces:**
- Consumes: Task 2's 7-arg `StewardAuthorization`, `StewardReview`, `StewardVeto`, `StewardAppeal`, and the four `append*` methods on `StewardAuthorizationStore`.
- Produces: `CouchDbStewardAuthorizationStore(String baseUrl, String database, String user, String password, ObjectMapper mapper)`, a `StewardAuthorizationStore` implementation Task 9's `StewardResource` runs against when `benefits.steward.persistence.enabled=true`.

`InMemoryStewardAuthorizationStore`'s `ConcurrentHashMap.compute()` (Task 2) is atomic per key in the JVM, but CouchDB has no such primitive — only optimistic concurrency via `_rev`. `CouchDbAuditStore.put()`'s existing read-then-write pattern (no `_rev` conflict retry) is sufficient there because nothing writes the same audit document concurrently; it is NOT sufficient here, because two stewards co-signing the same release request from separate HTTP requests (Task 9) is precisely the concurrent write path `StewardAuthorizationStore`'s own doc comment (Task 2) says must be handled. This task's append methods use a read-check-`_rev`-PUT-retry-on-409 loop instead.

- [ ] **Step 1: Write the failing integration test**

`CouchDbStewardAuthorizationStoreIntegrationTest.java`:
```java
package org.benefits.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CouchDbStewardAuthorizationStoreIntegrationTest {

    private CouchDbStewardAuthorizationStore store;

    @BeforeEach
    void setUp() {
        store = new CouchDbStewardAuthorizationStore("http://localhost:5984", "benefits-steward-test",
                "admin", "benefits", new ObjectMapper());
    }

    @Test
    void returnsNullWhenAbsent() {
        assertThat(store.findByReleaseRequestId("rr-missing-" + System.nanoTime())).isNull();
    }

    @Test
    void appendSignatureCreatesThenPersistsAcrossInstances() {
        var id = "rr-persist-" + System.nanoTime();
        var signature = new StewardSignature("s-1", "sig-s-1", Instant.parse("2026-09-01T00:00:00Z"));
        store.appendSignature(id, "hash-1", signature);

        var reloaded = new CouchDbStewardAuthorizationStore("http://localhost:5984", "benefits-steward-test",
                "admin", "benefits", new ObjectMapper());
        var found = reloaded.findByReleaseRequestId(id);
        assertThat(found).isNotNull();
        assertThat(found.signatures()).containsExactly(signature);
        assertThat(found.datasetHash()).isEqualTo("hash-1");
    }

    @Test
    void appendAppealFailsWhenNoVetoExists() {
        var id = "rr-appeal-fail-" + System.nanoTime();
        var appeal = new StewardAppeal("p-1", "reason", "s-nobody", Instant.parse("2026-09-01T00:00:00Z"));
        assertThatThrownBy(() -> store.appendAppeal(id, appeal))
                .isInstanceOf(org.eclipse.edc.spi.EdcException.class);
    }

    @Test
    void concurrentSignaturesFromTwoStewardsAreBothPersisted() throws InterruptedException {
        var id = "rr-concurrent-" + System.nanoTime();
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);

        Runnable signAs = (stewardId) -> {
        };
        pool.submit(() -> signAsSteward(id, "s-1", ready, go));
        pool.submit(() -> signAsSteward(id, "s-2", ready, go));

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        var result = store.findByReleaseRequestId(id);
        assertThat(result).isNotNull();
        assertThat(result.signatures()).hasSize(2);
        assertThat(result.distinctStewardCount()).isEqualTo(2);
    }

    private void signAsSteward(String releaseRequestId, String stewardId, CountDownLatch ready, CountDownLatch go) {
        try {
            ready.countDown();
            go.await();
            store.appendSignature(releaseRequestId, "hash-concurrent",
                    new StewardSignature(stewardId, "sig-" + stewardId, Instant.now()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

(The unused `signAs` local variable in the draft above is a mistake to fix while writing this step, not to copy: remove that line; it is not needed once `signAsSteward` is called directly via method reference inside the lambda-free `pool.submit(() -> signAsSteward(...))` calls.)

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :connector:extensions:benefits-policy:test --tests CouchDbStewardAuthorizationStoreIntegrationTest`
Expected: compile failure (class does not exist yet).

- [ ] **Step 3: Implement `CouchDbStewardAuthorizationStore`**

```java
package org.benefits.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.edc.spi.EdcException;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Persists StewardAuthorization as one CouchDB document per release request id,
 * keyed by releaseRequestId as _id.
 * <p>
 * The append* methods use a read-check_rev-PUT-retry-on-409 loop, not the plain
 * read-then-write CouchDbAuditStore.put() uses: two stewards co-signing the same
 * release request from concurrent HTTP requests (StewardResource) is exactly the
 * race StewardAuthorizationStore's own interface doc comment requires append to
 * survive without losing either steward's action.
 */
public class CouchDbStewardAuthorizationStore implements StewardAuthorizationStore {

    private static final int MAX_RETRIES = 10;

    private final String baseUrl;
    private final String database;
    private final String authHeader;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();

    public CouchDbStewardAuthorizationStore(String baseUrl, String database, String user, String password, ObjectMapper mapper) {
        this.baseUrl = baseUrl;
        this.database = database;
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes());
        this.mapper = mapper;
        ensureDatabaseExists();
    }

    private void ensureDatabaseExists() {
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + database))
                    .header("authorization", authHeader).PUT(HttpRequest.BodyPublishers.noBody()).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 201 && response.statusCode() != 412) {
                throw new EdcException("failed to create CouchDB database " + database
                        + ", status " + response.statusCode() + ": " + response.body());
            }
        } catch (IOException e) {
            throw new EdcException("failed to create CouchDB database " + database, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdcException("interrupted creating CouchDB database " + database, e);
        }
    }

    @Override
    @Nullable
    public StewardAuthorization findByReleaseRequestId(String releaseRequestId) {
        var doc = getDoc(releaseRequestId);
        return doc == null ? null : toAuthorization(doc);
    }

    @Override
    public void save(StewardAuthorization authorization) {
        var existing = getDoc(authorization.releaseRequestId());
        var rev = existing != null && existing.hasNonNull("_rev") ? existing.get("_rev").asText() : null;
        var status = putDoc(authorization.releaseRequestId(), authorization, rev);
        if (status != 200 && status != 201) {
            throw new EdcException("CouchDB put failed with status " + status
                    + " saving steward authorization " + authorization.releaseRequestId());
        }
    }

    @Override
    public StewardAuthorization appendReview(String releaseRequestId, String datasetHash, StewardReview review) {
        return appendWithRetry(releaseRequestId, datasetHash, base -> new StewardAuthorization(
                base.releaseRequestId(), base.datasetHash(), base.signatures(),
                append(base.reviews(), review), base.vetoes(), base.appeals(), base.issuedAt()));
    }

    @Override
    public StewardAuthorization appendSignature(String releaseRequestId, String datasetHash, StewardSignature signature) {
        return appendWithRetry(releaseRequestId, datasetHash, base -> new StewardAuthorization(
                base.releaseRequestId(), base.datasetHash(), append(base.signatures(), signature),
                base.reviews(), base.vetoes(), base.appeals(), base.issuedAt()));
    }

    @Override
    public StewardAuthorization appendVeto(String releaseRequestId, String datasetHash, StewardVeto veto) {
        return appendWithRetry(releaseRequestId, datasetHash, base -> new StewardAuthorization(
                base.releaseRequestId(), base.datasetHash(), base.signatures(),
                base.reviews(), append(base.vetoes(), veto), base.appeals(), base.issuedAt()));
    }

    @Override
    public StewardAuthorization appendAppeal(String releaseRequestId, StewardAppeal appeal) {
        var existing = findByReleaseRequestId(releaseRequestId);
        if (existing == null) {
            throw new EdcException("cannot appeal a veto for release request with no steward authorization: " + releaseRequestId);
        }
        var vetoExists = existing.vetoes().stream().anyMatch(v -> v.stewardId().equals(appeal.vetoStewardId()));
        if (!vetoExists) {
            throw new EdcException("no veto by " + appeal.vetoStewardId() + " to appeal for release request " + releaseRequestId);
        }
        return appendWithRetry(releaseRequestId, existing.datasetHash(), base -> new StewardAuthorization(
                base.releaseRequestId(), base.datasetHash(), base.signatures(),
                base.reviews(), base.vetoes(), append(base.appeals(), appeal), base.issuedAt()));
    }

    private StewardAuthorization appendWithRetry(String releaseRequestId, String datasetHash,
                                                   UnaryOperator<StewardAuthorization> mutate) {
        for (var attempt = 0; attempt < MAX_RETRIES; attempt++) {
            var existingDoc = getDoc(releaseRequestId);
            var base = existingDoc != null ? toAuthorization(existingDoc) : empty(releaseRequestId, datasetHash);
            var updated = mutate.apply(base);
            var rev = existingDoc != null && existingDoc.hasNonNull("_rev") ? existingDoc.get("_rev").asText() : null;
            var status = putDoc(releaseRequestId, updated, rev);
            if (status == 200 || status == 201) {
                return updated;
            }
            if (status != 409) {
                throw new EdcException("CouchDB put failed with status " + status + " writing steward authorization " + releaseRequestId);
            }
        }
        throw new EdcException("exceeded retry limit appending to steward authorization " + releaseRequestId);
    }

    private static StewardAuthorization empty(String releaseRequestId, String datasetHash) {
        return new StewardAuthorization(releaseRequestId, datasetHash, List.of(), List.of(), List.of(), List.of(), Instant.now());
    }

    private static <T> List<T> append(List<T> list, T item) {
        var copy = new ArrayList<>(list);
        copy.add(item);
        return List.copyOf(copy);
    }

    private JsonNode getDoc(String id) {
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + database + "/" + id))
                    .header("authorization", authHeader).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() != 200) {
                throw new EdcException("CouchDB get failed with status " + response.statusCode());
            }
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new EdcException("CouchDB get failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdcException("CouchDB get interrupted", e);
        }
    }

    private int putDoc(String id, StewardAuthorization authorization, @Nullable String rev) {
        var doc = toJson(authorization);
        if (rev != null) {
            doc.put("_rev", rev);
        }
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + database + "/" + id))
                    .header("authorization", authHeader).header("content-type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(doc))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode();
        } catch (IOException e) {
            throw new EdcException("CouchDB put failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdcException("CouchDB put interrupted", e);
        }
    }

    private ObjectNode toJson(StewardAuthorization authorization) {
        var doc = mapper.createObjectNode();
        doc.put("_id", authorization.releaseRequestId());
        doc.put("type", "stewardAuthorization");
        doc.put("releaseRequestId", authorization.releaseRequestId());
        doc.put("datasetHash", authorization.datasetHash());
        doc.put("issuedAt", authorization.issuedAt().toString());
        var signatures = doc.putArray("signatures");
        for (var s : authorization.signatures()) {
            signatures.addObject().put("stewardId", s.stewardId()).put("signature", s.signature())
                    .put("signedAt", s.signedAt().toString());
        }
        var reviews = doc.putArray("reviews");
        for (var r : authorization.reviews()) {
            reviews.addObject().put("stewardId", r.stewardId()).put("rubricScore", r.rubricScore())
                    .put("notes", r.notes()).put("reviewedAt", r.reviewedAt().toString());
        }
        var vetoes = doc.putArray("vetoes");
        for (var v : authorization.vetoes()) {
            vetoes.addObject().put("stewardId", v.stewardId()).put("reason", v.reason())
                    .put("vetoedAt", v.vetoedAt().toString());
        }
        var appeals = doc.putArray("appeals");
        for (var a : authorization.appeals()) {
            appeals.addObject().put("appellantId", a.appellantId()).put("reason", a.reason())
                    .put("vetoStewardId", a.vetoStewardId()).put("appealedAt", a.appealedAt().toString());
        }
        return doc;
    }

    private StewardAuthorization toAuthorization(JsonNode doc) {
        List<StewardSignature> signatures = new ArrayList<>();
        for (var node : doc.path("signatures")) {
            signatures.add(new StewardSignature(node.get("stewardId").asText(), node.get("signature").asText(),
                    Instant.parse(node.get("signedAt").asText())));
        }
        List<StewardReview> reviews = new ArrayList<>();
        for (var node : doc.path("reviews")) {
            reviews.add(new StewardReview(node.get("stewardId").asText(), node.get("rubricScore").asInt(),
                    node.get("notes").asText(), Instant.parse(node.get("reviewedAt").asText())));
        }
        List<StewardVeto> vetoes = new ArrayList<>();
        for (var node : doc.path("vetoes")) {
            vetoes.add(new StewardVeto(node.get("stewardId").asText(), node.get("reason").asText(),
                    Instant.parse(node.get("vetoedAt").asText())));
        }
        List<StewardAppeal> appeals = new ArrayList<>();
        for (var node : doc.path("appeals")) {
            appeals.add(new StewardAppeal(node.get("appellantId").asText(), node.get("reason").asText(),
                    node.get("vetoStewardId").asText(), Instant.parse(node.get("appealedAt").asText())));
        }
        return new StewardAuthorization(doc.get("releaseRequestId").asText(), doc.get("datasetHash").asText(),
                signatures, reviews, vetoes, appeals, Instant.parse(doc.get("issuedAt").asText()));
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :connector:extensions:benefits-policy:test --tests CouchDbStewardAuthorizationStoreIntegrationTest`
Expected: PASS (requires live CouchDB at `localhost:5984`, `admin:benefits`, per README).

- [ ] **Step 5: Wire persistence into `BenefitsPolicyExtension`, feature-flagged like audit**

Change the `store` field from a `final` field-initializer to one built during `initialize()`:

```java
    private StewardAuthorizationStore store;

    @Provider
    public StewardAuthorizationStore stewardAuthorizationStore() {
        return store;
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        var monitor = context.getMonitor();
        store = buildStore(context);

        ruleBindingRegistry.bind(ODRL_USE_ACTION_ATTRIBUTE, ALL_SCOPES);
        for (var key : new String[]{PROBE_KEY, RELEASE_REQUEST_KEY, STEWARD_QUORUM_KEY, PURPOSE_KEY, RECIPIENT_CLASS_KEY, FIELD_SET_KEY}) {
            ruleBindingRegistry.bind(key, NEGOTIATION_SCOPE);
        }

        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class, PROBE_KEY,
                new ProbeConstraintFunction(monitor));
        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class, RELEASE_REQUEST_KEY,
                (operator, rightValue, rule, policyContext) -> true);
        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class, STEWARD_QUORUM_KEY,
                new StewardQuorumFunction(store, monitor));
        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class, PURPOSE_KEY,
                new PurposeFunction());
        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class, RECIPIENT_CLASS_KEY,
                new RecipientClassFunction());
        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class, FIELD_SET_KEY,
                new FieldSetFunction());

        monitor.info("BENEFITS policy functions registered");

        seed(context);
    }

    private StewardAuthorizationStore buildStore(ServiceExtensionContext context) {
        if (!Boolean.parseBoolean(context.getSetting("benefits.steward.persistence.enabled", "false"))) {
            return new InMemoryStewardAuthorizationStore();
        }
        var url = context.getSetting("benefits.couchdb.url", "http://localhost:5984");
        var database = context.getSetting("benefits.couchdb.database", "benefits") + "-steward";
        var user = context.getSetting("benefits.couchdb.user", "admin");
        var password = context.getSetting("benefits.couchdb.password", "");
        return new CouchDbStewardAuthorizationStore(url, database, user, password,
                new com.fasterxml.jackson.databind.ObjectMapper());
    }
```

The rest of the file (`name()`, `seed(ServiceExtensionContext)`) is unchanged; `seed()` already refers to `store` as a field, not a local, so it needs no edit.

**No `build.gradle.kts` change in this task.** `StewardResource` (Task 9) is registered from `benefits-privacy`'s extension, not this module's -- `benefits-privacy` already depends on `benefits-policy` (`implementation(project(":connector:extensions:benefits-policy"))`, existing), so it can `@Inject StewardAuthorizationStore` across the module boundary via EDC's own service injection, the same way `StewardQuorumFunction` already does within this module. The reverse dependency does not exist (`benefits-policy` cannot see `benefits-privacy`'s classes), and `StewardResource`'s queue needs both `StewardAuthorizationStore` (this module) and `ReleaseRequestStore` (Task 4, `benefits-privacy`) joined together -- it can only live where both are visible, which is `benefits-privacy`. This module (`benefits-policy`) never gains a `WebService`/`web.spi` dependency under this plan.

- [ ] **Step 6: Run the whole module's test suite to confirm no regression**

Run: `./gradlew :connector:extensions:benefits-policy:test`
Expected: PASS, including `StewardQuorumFunctionTest` (updated in Task 2) and every other existing test in this module.

- [ ] **Step 7: Commit**

```bash
git add connector/extensions/benefits-policy/src/main/java/org/benefits/policy/CouchDbStewardAuthorizationStore.java \
        connector/extensions/benefits-policy/src/test/java/org/benefits/policy/CouchDbStewardAuthorizationStoreIntegrationTest.java \
        connector/extensions/benefits-policy/src/main/java/org/benefits/policy/BenefitsPolicyExtension.java
git commit -m "feat(benefits-policy): persist steward authorizations to CouchDB with atomic append"
```

### Task 4: ReleaseRequest domain and store (in-memory + CouchDB)

**Files:**
- Create: `connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/ReleaseRequest.java`
- Create: `connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/ReleaseRequestStore.java`
- Create: `connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/InMemoryReleaseRequestStore.java`
- Create: `connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/CouchDbReleaseRequestStore.java`
- Create: `connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/InMemoryReleaseRequestStoreTest.java`
- Create: `connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/CouchDbReleaseRequestStoreIntegrationTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `ReleaseRequest(String releaseRequestId, String purpose, String recipientClass, List<String> fields, String siteId, Instant requestedAt)`. `ReleaseRequestStore.save/findById/all()`. Task 7 (`POST /release-requests`) creates these; Task 9 (`StewardResource`'s queue) reads `all()` and joins each with its `StewardAuthorizationStore` entry to build the review queue.

**IMPORTANT — package note confirmed by grounding research:** `benefits-privacy`'s Gradle module lives at `connector/extensions/benefits-privacy/`, but its Java package is `org.benefits.policy` — the same package `benefits-policy`'s own classes use, just a different module directory. Do not create `org.benefits.privacy` — it does not exist anywhere in this codebase.

No entity in this codebase today stores *what was requested* (purpose, recipient class, fields, site) for a steward to review against — `StewardAuthorizationStore` only ever stores decisions (signatures, and now reviews/vetoes/appeals) keyed by a release request id that something else must have minted first. `ReleaseRequestStore` is that something else. `save()` uses plain replace semantics deliberately: a release request is created once (Task 7) and never mutated afterward — unlike steward authorizations, there is no concurrent-writer scenario here, so no atomic append is needed (mirrors `ReleaseArtifactStore`'s own doc comment reasoning, Task 5).

- [ ] **Step 1: Write the failing `InMemoryReleaseRequestStoreTest`**

```java
package org.benefits.policy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryReleaseRequestStoreTest {

    private final ReleaseRequestStore store = new InMemoryReleaseRequestStore();

    @Test
    void returnsNullWhenAbsent() {
        assertThat(store.findById("missing")).isNull();
    }

    @Test
    void roundTripsARequest() {
        var request = new ReleaseRequest("rr-1", "service-improvement", "statistics",
                List.of("service.satisfaction"), "athens", Instant.parse("2026-09-01T00:00:00Z"));
        store.save(request);
        assertThat(store.findById("rr-1")).isEqualTo(request);
    }

    @Test
    void allReturnsEveryStoredRequest() {
        store.save(new ReleaseRequest("rr-2", "service-improvement", "statistics",
                List.of("service.satisfaction"), "athens", Instant.parse("2026-09-01T00:00:00Z")));
        store.save(new ReleaseRequest("rr-3", "marketing", "provider",
                List.of("health.condition"), "thessaloniki", Instant.parse("2026-09-01T01:00:00Z")));
        assertThat(store.all()).extracting(ReleaseRequest::releaseRequestId).contains("rr-2", "rr-3");
    }
}
```

- [ ] **Step 2: Run to verify it fails to compile**

Run: `./gradlew :connector:extensions:benefits-privacy:test --tests InMemoryReleaseRequestStoreTest`
Expected: compile failure (none of these types exist yet).

- [ ] **Step 3: Implement the domain type and interface**

`ReleaseRequest.java`:
```java
package org.benefits.policy;

import java.time.Instant;
import java.util.List;

public record ReleaseRequest(
        String releaseRequestId,
        String purpose,
        String recipientClass,
        List<String> fields,
        String siteId,
        Instant requestedAt) {

    public ReleaseRequest {
        fields = List.copyOf(fields);
    }
}
```

`ReleaseRequestStore.java`:
```java
package org.benefits.policy;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Holds what was asked for, keyed by release request id -- distinct from
 * StewardAuthorizationStore, which holds what stewards decided about it. A request is
 * created once (the POST /release-requests handler) and never mutated afterward, so
 * save() is a plain replace with no concurrent-writer scenario to guard against,
 * unlike StewardAuthorizationStore's append* methods.
 */
public interface ReleaseRequestStore {

    @Nullable
    ReleaseRequest findById(String releaseRequestId);

    void save(ReleaseRequest request);

    List<ReleaseRequest> all();
}
```

`InMemoryReleaseRequestStore.java`:
```java
package org.benefits.policy;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryReleaseRequestStore implements ReleaseRequestStore {

    private final Map<String, ReleaseRequest> byId = new ConcurrentHashMap<>();

    @Override
    @Nullable
    public ReleaseRequest findById(String releaseRequestId) {
        return byId.get(releaseRequestId);
    }

    @Override
    public void save(ReleaseRequest request) {
        byId.put(request.releaseRequestId(), request);
    }

    @Override
    public List<ReleaseRequest> all() {
        return List.copyOf(byId.values());
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :connector:extensions:benefits-privacy:test --tests InMemoryReleaseRequestStoreTest`
Expected: PASS.

- [ ] **Step 5: Write the failing `CouchDbReleaseRequestStoreIntegrationTest`**

```java
package org.benefits.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CouchDbReleaseRequestStoreIntegrationTest {

    private CouchDbReleaseRequestStore store;

    @BeforeEach
    void setUp() {
        store = new CouchDbReleaseRequestStore("http://localhost:5984", "benefits-requests-test",
                "admin", "benefits", new ObjectMapper());
    }

    @Test
    void roundTripsAcrossInstances() {
        var id = "rr-persist-" + System.nanoTime();
        var request = new ReleaseRequest(id, "service-improvement", "statistics",
                List.of("service.satisfaction"), "athens", Instant.parse("2026-09-01T00:00:00Z"));
        store.save(request);

        var reloaded = new CouchDbReleaseRequestStore("http://localhost:5984", "benefits-requests-test",
                "admin", "benefits", new ObjectMapper());
        assertThat(reloaded.findById(id)).isEqualTo(request);
    }

    @Test
    void allIncludesEveryPersistedRequest() {
        var id = "rr-all-" + System.nanoTime();
        store.save(new ReleaseRequest(id, "service-improvement", "statistics",
                List.of("service.satisfaction"), "athens", Instant.parse("2026-09-01T00:00:00Z")));
        assertThat(store.all()).extracting(ReleaseRequest::releaseRequestId).contains(id);
    }
}
```

- [ ] **Step 6: Run to verify it fails to compile**

Run: `./gradlew :connector:extensions:benefits-privacy:test --tests CouchDbReleaseRequestStoreIntegrationTest`
Expected: compile failure.

- [ ] **Step 7: Implement `CouchDbReleaseRequestStore`**, mirroring `CouchDbAuditStore`'s `find`/`getDoc`/`put` pattern exactly (single-writer, no retry loop needed):

```java
package org.benefits.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.edc.spi.EdcException;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class CouchDbReleaseRequestStore implements ReleaseRequestStore {

    private static final int MAX_RESULTS = 100_000;

    private final String baseUrl;
    private final String database;
    private final String authHeader;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();

    public CouchDbReleaseRequestStore(String baseUrl, String database, String user, String password, ObjectMapper mapper) {
        this.baseUrl = baseUrl;
        this.database = database;
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes());
        this.mapper = mapper;
        ensureDatabaseExists();
    }

    private void ensureDatabaseExists() {
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + database))
                    .header("authorization", authHeader).PUT(HttpRequest.BodyPublishers.noBody()).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 201 && response.statusCode() != 412) {
                throw new EdcException("failed to create CouchDB database " + database
                        + ", status " + response.statusCode() + ": " + response.body());
            }
        } catch (IOException e) {
            throw new EdcException("failed to create CouchDB database " + database, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdcException("interrupted creating CouchDB database " + database, e);
        }
    }

    @Override
    @Nullable
    public ReleaseRequest findById(String releaseRequestId) {
        var doc = getDoc(releaseRequestId);
        return doc == null ? null : toRequest(doc);
    }

    @Override
    public void save(ReleaseRequest request) {
        var existing = getDoc(request.releaseRequestId());
        var doc = mapper.createObjectNode();
        doc.put("_id", request.releaseRequestId());
        doc.put("type", "releaseRequest");
        doc.put("releaseRequestId", request.releaseRequestId());
        doc.put("purpose", request.purpose());
        doc.put("recipientClass", request.recipientClass());
        var fields = doc.putArray("fields");
        request.fields().forEach(fields::add);
        doc.put("siteId", request.siteId());
        doc.put("requestedAt", request.requestedAt().toString());
        if (existing != null && existing.hasNonNull("_rev")) {
            doc.put("_rev", existing.get("_rev").asText());
        }
        put(request.releaseRequestId(), doc);
    }

    @Override
    public List<ReleaseRequest> all() {
        var docs = find("{\"selector\":{\"type\":\"releaseRequest\"},\"limit\":" + MAX_RESULTS + "}");
        List<ReleaseRequest> result = new ArrayList<>();
        for (var doc : docs.path("docs")) {
            result.add(toRequest(doc));
        }
        return result;
    }

    private ReleaseRequest toRequest(JsonNode doc) {
        List<String> fields = new ArrayList<>();
        for (var node : doc.path("fields")) {
            fields.add(node.asText());
        }
        return new ReleaseRequest(doc.get("releaseRequestId").asText(), doc.get("purpose").asText(),
                doc.get("recipientClass").asText(), fields, doc.get("siteId").asText(),
                Instant.parse(doc.get("requestedAt").asText()));
    }

    private JsonNode getDoc(String id) {
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + database + "/" + id))
                    .header("authorization", authHeader).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() != 200) {
                throw new EdcException("CouchDB get failed with status " + response.statusCode());
            }
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new EdcException("CouchDB get failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdcException("CouchDB get interrupted", e);
        }
    }

    private void put(String id, JsonNode doc) {
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + database + "/" + id))
                    .header("authorization", authHeader).header("content-type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(doc))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 201 && response.statusCode() != 200) {
                throw new EdcException("CouchDB put failed with status " + response.statusCode() + ": " + response.body());
            }
        } catch (IOException e) {
            throw new EdcException("CouchDB put failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdcException("CouchDB put interrupted", e);
        }
    }

    private JsonNode find(String selectorJson) {
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + database + "/_find"))
                    .header("authorization", authHeader).header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(selectorJson)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new EdcException("CouchDB query failed with status " + response.statusCode() + ": " + response.body());
            }
            var result = mapper.readTree(response.body());
            if (result.path("docs").size() == MAX_RESULTS) {
                throw new EdcException("CouchDB query may have been truncated at the limit of " + MAX_RESULTS + " results");
            }
            return result;
        } catch (IOException e) {
            throw new EdcException("CouchDB query failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdcException("CouchDB query interrupted", e);
        }
    }
}
```

- [ ] **Step 8: Run to verify it passes**

Run: `./gradlew :connector:extensions:benefits-privacy:test --tests CouchDbReleaseRequestStoreIntegrationTest`
Expected: PASS (requires live CouchDB).

- [ ] **Step 9: Commit**

```bash
git add connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/ReleaseRequest.java \
        connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/ReleaseRequestStore.java \
        connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/InMemoryReleaseRequestStore.java \
        connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/CouchDbReleaseRequestStore.java \
        connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/InMemoryReleaseRequestStoreTest.java \
        connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/CouchDbReleaseRequestStoreIntegrationTest.java
git commit -m "feat(benefits-privacy): add ReleaseRequest domain and store"
```

### Task 5: CouchDbReleaseArtifactStore

**Files:**
- Create: `connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/CouchDbReleaseArtifactStore.java`
- Create: `connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/CouchDbReleaseArtifactStoreIntegrationTest.java`
- Modify: `connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/BenefitsPrivacyExtension.java`
- Modify: `connector/extensions/benefits-privacy/build.gradle.kts`

**Interfaces:**
- Consumes: nothing new from earlier tasks (parallels `ReleaseArtifactStore`, unchanged interface).
- Produces: `CouchDbReleaseArtifactStore(String baseUrl, String database, String user, String password, ObjectMapper mapper)` implementing `ReleaseArtifactStore`. Task 7's release-request endpoint persists `PrivacyPipeline.compute()`'s output through this when persistence is enabled.

`ReleaseArtifactStore`'s own doc comment (unchanged, quoted in spec §5.3's reasoning): replace semantics is *acceptable* here, unlike the steward store — "replacing an artifact loses data rather than violating a privacy guarantee, because the budget was already charged when the artifact was computed." No retry loop needed; this mirrors `CouchDbAuditStore`'s plain read-then-write `put()`.

- [ ] **Step 1: Write the failing integration test**

```java
package org.benefits.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CouchDbReleaseArtifactStoreIntegrationTest {

    private CouchDbReleaseArtifactStore store;

    @BeforeEach
    void setUp() {
        store = new CouchDbReleaseArtifactStore("http://localhost:5984", "benefits-artifacts-test",
                "admin", "benefits", new ObjectMapper());
    }

    @Test
    void returnsNullWhenAbsent() {
        assertThat(store.findByReleaseRequestId("missing-" + System.nanoTime())).isNull();
    }

    @Test
    void roundTripsBytesUnchangedAcrossInstances() {
        var id = "rr-artifact-" + System.nanoTime();
        var payload = "{\"groups\":[{\"group\":\"app\",\"count\":12}]}".getBytes(StandardCharsets.UTF_8);
        store.save(new ReleaseArtifact(id, payload, 6, 0.5));

        var reloaded = new CouchDbReleaseArtifactStore("http://localhost:5984", "benefits-artifacts-test",
                "admin", "benefits", new ObjectMapper());
        var found = reloaded.findByReleaseRequestId(id);
        assertThat(found).isNotNull();
        assertThat(found.bytes()).isEqualTo(payload);
        assertThat(found.computedK()).isEqualTo(6);
        assertThat(found.epsilonCharged()).isEqualTo(0.5);
    }
}
```

- [ ] **Step 2: Run to verify it fails to compile**

Run: `./gradlew :connector:extensions:benefits-privacy:test --tests CouchDbReleaseArtifactStoreIntegrationTest`
Expected: compile failure.

- [ ] **Step 3: Implement it**, base64-encoding `bytes` for CouchDB JSON storage (CouchDB documents are JSON; raw bytes are not valid JSON values):

```java
package org.benefits.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.edc.spi.EdcException;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

public class CouchDbReleaseArtifactStore implements ReleaseArtifactStore {

    private final String baseUrl;
    private final String database;
    private final String authHeader;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();

    public CouchDbReleaseArtifactStore(String baseUrl, String database, String user, String password, ObjectMapper mapper) {
        this.baseUrl = baseUrl;
        this.database = database;
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes());
        this.mapper = mapper;
        ensureDatabaseExists();
    }

    private void ensureDatabaseExists() {
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + database))
                    .header("authorization", authHeader).PUT(HttpRequest.BodyPublishers.noBody()).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 201 && response.statusCode() != 412) {
                throw new EdcException("failed to create CouchDB database " + database
                        + ", status " + response.statusCode() + ": " + response.body());
            }
        } catch (IOException e) {
            throw new EdcException("failed to create CouchDB database " + database, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdcException("interrupted creating CouchDB database " + database, e);
        }
    }

    @Override
    @Nullable
    public ReleaseArtifact findByReleaseRequestId(String releaseRequestId) {
        var doc = getDoc(releaseRequestId);
        if (doc == null) {
            return null;
        }
        var bytes = Base64.getDecoder().decode(doc.get("bytesBase64").asText());
        return new ReleaseArtifact(doc.get("releaseRequestId").asText(), bytes,
                doc.get("computedK").asInt(), doc.get("epsilonCharged").asDouble());
    }

    @Override
    public void save(ReleaseArtifact artifact) {
        var existing = getDoc(artifact.releaseRequestId());
        var doc = mapper.createObjectNode();
        doc.put("_id", artifact.releaseRequestId());
        doc.put("type", "releaseArtifact");
        doc.put("releaseRequestId", artifact.releaseRequestId());
        doc.put("bytesBase64", Base64.getEncoder().encodeToString(artifact.bytes()));
        doc.put("computedK", artifact.computedK());
        doc.put("epsilonCharged", artifact.epsilonCharged());
        if (existing != null && existing.hasNonNull("_rev")) {
            doc.put("_rev", existing.get("_rev").asText());
        }
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + database + "/" + artifact.releaseRequestId()))
                    .header("authorization", authHeader).header("content-type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(doc))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 201 && response.statusCode() != 200) {
                throw new EdcException("CouchDB put failed with status " + response.statusCode() + ": " + response.body());
            }
        } catch (IOException e) {
            throw new EdcException("CouchDB put failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdcException("CouchDB put interrupted", e);
        }
    }

    private JsonNode getDoc(String id) {
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + database + "/" + id))
                    .header("authorization", authHeader).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() != 200) {
                throw new EdcException("CouchDB get failed with status " + response.statusCode());
            }
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new EdcException("CouchDB get failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdcException("CouchDB get interrupted", e);
        }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :connector:extensions:benefits-privacy:test --tests CouchDbReleaseArtifactStoreIntegrationTest`
Expected: PASS (requires live CouchDB).

- [ ] **Step 5: Wire persistence into `BenefitsPrivacyExtension`, feature-flagged**

```java
    private ReleaseArtifactStore artifactStore;

    @Provider
    public ReleaseArtifactStore releaseArtifactStore() {
        return artifactStore;
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        var monitor = context.getMonitor();
        artifactStore = buildArtifactStore(context);
        pipelineService.registerFactory(new ProbeDataSourceFactory());
        pipelineService.registerFactory(new BenefitsAggregateDataSourceFactory(artifactStore));

        ruleBindingRegistry.bind(K_ANONYMITY_KEY, NEGOTIATION_SCOPE);
        ruleBindingRegistry.bind(EPSILON_BUDGET_KEY, NEGOTIATION_SCOPE);
        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class,
                K_ANONYMITY_KEY, new KAnonymityFunction(artifactStore, monitor));
        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class,
                EPSILON_BUDGET_KEY, new EpsilonBudgetFunction(artifactStore, monitor));

        monitor.info("BENEFITS privacy pipeline registered");

        seed(context);
    }

    private ReleaseArtifactStore buildArtifactStore(ServiceExtensionContext context) {
        if (!Boolean.parseBoolean(context.getSetting("benefits.privacy.persistence.enabled", "false"))) {
            return new InMemoryReleaseArtifactStore();
        }
        var url = context.getSetting("benefits.couchdb.url", "http://localhost:5984");
        var database = context.getSetting("benefits.couchdb.database", "benefits") + "-artifacts";
        var user = context.getSetting("benefits.couchdb.user", "admin");
        var password = context.getSetting("benefits.couchdb.password", "");
        return new CouchDbReleaseArtifactStore(url, database, user, password, new com.fasterxml.jackson.databind.ObjectMapper());
    }
```

`seed(ServiceExtensionContext)` is unchanged (already refers to the `artifactStore` field, not a local).

- [ ] **Step 6: `build.gradle.kts`**, add the Jackson JSR310 module dependency it does not yet have (Task 6 needs it too, and Task 7's endpoint parses `Instant` request timestamps):

```kotlin
plugins {
    `java-library`
}

dependencies {
    api(libs.edc.control.plane.spi)
    api(libs.edc.policy.engine.spi)
    api(libs.edc.data.plane.spi)
    api(libs.edc.web.spi)
    implementation(libs.edc.control.plane.core)
    implementation(project(":connector:extensions:benefits-policy"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.2")
}
```

- [ ] **Step 7: Run the whole module's test suite to confirm no regression**

Run: `./gradlew :connector:extensions:benefits-privacy:test`
Expected: PASS, including `BenefitsAggregateDataSourceFactoryTest` (unaffected -- it constructs its own `InMemoryReleaseArtifactStore` directly, not through the extension).

- [ ] **Step 8: Commit**

```bash
git add connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/CouchDbReleaseArtifactStore.java \
        connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/CouchDbReleaseArtifactStoreIntegrationTest.java \
        connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/BenefitsPrivacyExtension.java \
        connector/extensions/benefits-privacy/build.gradle.kts
git commit -m "feat(benefits-privacy): persist release artifacts to CouchDB"
```

### Task 6: CouchDbBudgetAccountant

**Files:**
- Create: `connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/CouchDbBudgetAccountant.java`
- Create: `connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/CouchDbBudgetAccountantIntegrationTest.java`
- Modify: `connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/BenefitsPrivacyExtension.java`

**Interfaces:**
- Consumes: nothing new from earlier tasks.
- Produces: `CouchDbBudgetAccountant(String baseUrl, String database, String user, String password, ObjectMapper mapper, double total)` implementing `BudgetAccountant`. Task 7's release-request endpoint charges epsilon through this when persistence is enabled.

`BudgetAccountant`'s own doc comment (unchanged) is explicit: `reserve` must be atomic because "two callers each reading the same remaining budget and each deciding they can afford it would overspend... a privacy guarantee violated." `InMemoryBudgetAccountant` gets this via `AtomicReference.compareAndSet` in a spin loop. CouchDB has no in-process compare-and-set; the equivalent is optimistic concurrency via `_rev` -- read the current remaining balance and its `_rev`, decide, `PUT` with that `_rev`, and on a `409` (someone else's reservation landed first) retry the whole read-decide-write cycle rather than blindly retrying the write. This is the same reasoning as Task 3's `appendWithRetry`, but here the budget check itself, not just the append, must happen inside the loop on each retry -- checking staleness once before the loop would let a concurrent reservation slip through between the check and a later attempt's write.

- [ ] **Step 1: Write the failing integration test**

```java
package org.benefits.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CouchDbBudgetAccountantIntegrationTest {

    private CouchDbBudgetAccountant accountant;

    @BeforeEach
    void setUp() {
        accountant = new CouchDbBudgetAccountant("http://localhost:5984", "benefits-budget-test",
                "admin", "benefits", new ObjectMapper(), 1.0);
    }

    @Test
    void remainingStartsAtTotalForAnUnseenSite() {
        assertThat(accountant.remaining("site-fresh-" + System.nanoTime())).isEqualTo(1.0);
    }

    @Test
    void reserveDeductsFromRemaining() {
        var site = "site-deduct-" + System.nanoTime();
        assertThat(accountant.reserve(site, 0.3)).isTrue();
        assertThat(accountant.remaining(site)).isEqualTo(0.7);
    }

    @Test
    void reserveFailsAndChargesNothingWhenInsufficient() {
        var site = "site-insufficient-" + System.nanoTime();
        accountant.reserve(site, 0.9);
        assertThat(accountant.reserve(site, 0.5)).isFalse();
        assertThat(accountant.remaining(site)).isEqualTo(0.1);
    }

    @Test
    void reservePersistsAcrossInstances() {
        var site = "site-persist-" + System.nanoTime();
        accountant.reserve(site, 0.4);
        var reloaded = new CouchDbBudgetAccountant("http://localhost:5984", "benefits-budget-test",
                "admin", "benefits", new ObjectMapper(), 1.0);
        assertThat(reloaded.remaining(site)).isEqualTo(0.6);
    }

    @Test
    void concurrentReservesNeverOverspend() throws InterruptedException {
        var site = "site-concurrent-" + System.nanoTime();
        var pool = Executors.newFixedThreadPool(4);
        var go = new CountDownLatch(1);
        var successCount = new AtomicInteger(0);

        for (var i = 0; i < 4; i++) {
            pool.submit(() -> {
                try {
                    go.await();
                    if (accountant.reserve(site, 0.3)) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();

        // total=1.0, epsilon=0.3 per reserve: at most 3 of the 4 concurrent attempts
        // can succeed (3 * 0.3 = 0.9 <= 1.0 < 4 * 0.3 = 1.2). If the accountant is not
        // truly atomic, more than 3 can succeed, overspending the budget.
        assertThat(successCount.get()).isLessThanOrEqualTo(3);
        assertThat(accountant.remaining(site)).isGreaterThanOrEqualTo(0.0);
    }
}
```

- [ ] **Step 2: Run to verify it fails to compile**

Run: `./gradlew :connector:extensions:benefits-privacy:test --tests CouchDbBudgetAccountantIntegrationTest`
Expected: compile failure.

- [ ] **Step 3: Implement it**

```java
package org.benefits.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.edc.spi.EdcException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

/**
 * CouchDB-backed differential privacy budget accountant, one document per site id.
 * reserve() uses a read-check-PUT-with-_rev-retry-on-409 loop: the budget check must
 * happen fresh on every retry attempt, not once before the loop, or a concurrent
 * reservation that lands between this call's check and a later attempt's write would
 * let both succeed and overspend -- exactly the violation BudgetAccountant's own doc
 * comment says reserve must never allow.
 */
public class CouchDbBudgetAccountant implements BudgetAccountant {

    private static final int MAX_RETRIES = 20;

    private final String baseUrl;
    private final String database;
    private final String authHeader;
    private final ObjectMapper mapper;
    private final double total;
    private final HttpClient client = HttpClient.newHttpClient();

    public CouchDbBudgetAccountant(String baseUrl, String database, String user, String password,
                                    ObjectMapper mapper, double total) {
        this.baseUrl = baseUrl;
        this.database = database;
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes());
        this.mapper = mapper;
        this.total = total;
        ensureDatabaseExists();
    }

    private void ensureDatabaseExists() {
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + database))
                    .header("authorization", authHeader).PUT(HttpRequest.BodyPublishers.noBody()).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 201 && response.statusCode() != 412) {
                throw new EdcException("failed to create CouchDB database " + database
                        + ", status " + response.statusCode() + ": " + response.body());
            }
        } catch (IOException e) {
            throw new EdcException("failed to create CouchDB database " + database, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdcException("interrupted creating CouchDB database " + database, e);
        }
    }

    @Override
    public boolean reserve(String siteId, double epsilon) {
        if (epsilon <= 0) {
            return false;
        }
        for (var attempt = 0; attempt < MAX_RETRIES; attempt++) {
            var doc = getDoc(siteId);
            var current = doc != null ? doc.get("remaining").asDouble() : total;
            if (current < epsilon) {
                return false;
            }
            var next = current - epsilon;
            var body = mapper.createObjectNode();
            body.put("_id", siteId);
            body.put("type", "budget");
            body.put("siteId", siteId);
            body.put("remaining", next);
            var rev = doc != null && doc.hasNonNull("_rev") ? doc.get("_rev").asText() : null;
            if (rev != null) {
                body.put("_rev", rev);
            }
            var status = put(siteId, body);
            if (status == 200 || status == 201) {
                return true;
            }
            if (status != 409) {
                throw new EdcException("CouchDB put failed with status " + status + " reserving budget for " + siteId);
            }
        }
        throw new EdcException("exceeded retry limit reserving budget for " + siteId);
    }

    @Override
    public double remaining(String siteId) {
        var doc = getDoc(siteId);
        return doc != null ? doc.get("remaining").asDouble() : total;
    }

    private JsonNode getDoc(String id) {
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + database + "/" + id))
                    .header("authorization", authHeader).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() != 200) {
                throw new EdcException("CouchDB get failed with status " + response.statusCode());
            }
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new EdcException("CouchDB get failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdcException("CouchDB get interrupted", e);
        }
    }

    private int put(String id, JsonNode doc) {
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + database + "/" + id))
                    .header("authorization", authHeader).header("content-type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(doc))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode();
        } catch (IOException e) {
            throw new EdcException("CouchDB put failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdcException("CouchDB put interrupted", e);
        }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :connector:extensions:benefits-privacy:test --tests CouchDbBudgetAccountantIntegrationTest`
Expected: PASS, including the concurrency test (requires live CouchDB; a genuinely non-atomic implementation would fail `concurrentReservesNeverOverspend` intermittently, not deterministically -- if this test is flaky, that is a real bug, not a test to loosen).

- [ ] **Step 5: `BenefitsPrivacyExtension` gains a settings-driven `BudgetAccountant`**

This extension does not construct a `BudgetAccountant` today at all -- `KAnonymityFunction`/`EpsilonBudgetFunction` (unaffected by this plan) read committed artifacts, not the accountant, and nothing in the current codebase builds one outside test code. Task 7's release-request endpoint is the first real caller. Add a package-visible builder method now, used by Task 7:

```java
    BudgetAccountant buildBudgetAccountant(ServiceExtensionContext context) {
        if (!Boolean.parseBoolean(context.getSetting("benefits.privacy.persistence.enabled", "false"))) {
            return new InMemoryBudgetAccountant(Double.parseDouble(context.getSetting("benefits.privacy.budget.total", "1.0")));
        }
        var url = context.getSetting("benefits.couchdb.url", "http://localhost:5984");
        var database = context.getSetting("benefits.couchdb.database", "benefits") + "-budget";
        var user = context.getSetting("benefits.couchdb.user", "admin");
        var password = context.getSetting("benefits.couchdb.password", "");
        var total = Double.parseDouble(context.getSetting("benefits.privacy.budget.total", "1.0"));
        return new CouchDbBudgetAccountant(url, database, user, password, new com.fasterxml.jackson.databind.ObjectMapper(), total);
    }
```

Add this method to the same class body as `buildArtifactStore` (Task 5). It reuses the same `benefits.privacy.persistence.enabled` flag Task 5 introduced -- one flag governs both new persisted stores in this extension, since they are enabled or disabled together for a given deployment.

- [ ] **Step 6: Run the whole module's test suite to confirm no regression**

Run: `./gradlew :connector:extensions:benefits-privacy:test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/CouchDbBudgetAccountant.java \
        connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/CouchDbBudgetAccountantIntegrationTest.java \
        connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/BenefitsPrivacyExtension.java
git commit -m "feat(benefits-privacy): persist the differential privacy budget to CouchDB"
```

### Task 7: POST /release-requests — real pipeline invocation

**Files:**
- Create: `connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/ReleaseRequestResource.java`
- Create: `connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/ReleaseRequestResourceTest.java`
- Modify: `connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/BenefitsPrivacyExtension.java`
- Modify: `tools/lib.sh`

**Interfaces:**
- Consumes: Task 4's `ReleaseRequest`/`ReleaseRequestStore`, Task 5's `CouchDbReleaseArtifactStore`/`ReleaseArtifactStore` (already wired as the `artifactStore` field), Task 6's `buildBudgetAccountant(context)`, and unmodified existing classes `PrivacyPipeline`, `QuerySpec`, `CouchDbContributionSource`, `LaplaceNoiseSource`.
- Produces: a live `POST /release-requests` HTTP endpoint. Task 9's `StewardResource` (same module) reads `ReleaseRequestStore.all()` to build its review queue, joining each `ReleaseRequest` with the matching `StewardAuthorizationStore` entry.

Closes phase 3's own stated gap (spec §6): today only `tools/run-case.sh` invokes `PrivacyPipeline` against contributions, through test-tooling JSON seeding, never a live request. `BenefitsAggregateDataSourceFactory`'s own doc comment confirms the intended architecture: "the artifact is produced once when the release request is created and stored" -- computation belongs at request-creation time, not at transfer time, which is exactly what this resource does.

- [ ] **Step 1: Write the failing resource test**

This is a JAX-RS resource test constructed directly (no live CouchDB needed for this particular test -- it uses an in-memory `ContributionSource`/`BudgetAccountant`/stores to test the resource's own request handling logic in isolation; Task 8's `StewardResource` tests take the same approach, and the CouchDB-backed classes underneath are already separately proven by Tasks 3, 4, 5, 6's own integration tests):

```java
package org.benefits.policy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseRequestResourceTest {

    private InMemoryContributionSource contributions;
    private ReleaseRequestStore requestStore;
    private ReleaseArtifactStore artifactStore;
    private BudgetAccountant budgetAccountant;
    private ReleaseRequestResource resource;

    @BeforeEach
    void setUp() {
        contributions = new InMemoryContributionSource();
        requestStore = new InMemoryReleaseRequestStore();
        artifactStore = new InMemoryReleaseArtifactStore();
        budgetAccountant = new InMemoryBudgetAccountant(1.0);
        resource = new ReleaseRequestResource(contributions, requestStore, artifactStore, budgetAccountant,
                new LaplaceNoiseSource(new SecureRandom()));
    }

    private void seedContributions(String siteId, int count) {
        for (var i = 0; i < count; i++) {
            contributions.add(new Contribution("p-" + i, siteId, Map.of("field.channel", "app")));
        }
    }

    @Test
    void createsAReleaseRequestAndPersistsTheArtifact() {
        seedContributions("athens", 8);
        var body = new ReleaseRequestResource.CreateReleaseRequest(
                "rr-test-1", "service-improvement", "statistics", List.of("channel"), "athens", 6, 0.5);

        var response = resource.create(body);

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(requestStore.findById("rr-test-1")).isNotNull();
        assertThat(artifactStore.findByReleaseRequestId("rr-test-1")).isNotNull();
    }

    @Test
    void returnsConflictAndChargesNothingWhenBudgetIsInsufficient() {
        seedContributions("athens", 8);
        budgetAccountant.reserve("athens", 0.9);
        var body = new ReleaseRequestResource.CreateReleaseRequest(
                "rr-test-2", "service-improvement", "statistics", List.of("channel"), "athens", 6, 0.5);

        var response = resource.create(body);

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(requestStore.findById("rr-test-2")).isNull();
        assertThat(artifactStore.findByReleaseRequestId("rr-test-2")).isNull();
    }

    @Test
    void rejectsAMissingReleaseRequestId() {
        var body = new ReleaseRequestResource.CreateReleaseRequest(
                "", "service-improvement", "statistics", List.of("channel"), "athens", 6, 0.5);

        var response = resource.create(body);

        assertThat(response.getStatus()).isEqualTo(400);
    }
}
```

`InMemoryContributionSource` is an existing class (per `benefits-privacy`'s existing test suite, `InMemoryContributionSourceTest.java`) -- confirm its exact constructor/`add` method by reading it before writing this step for real; if it does not expose an `add(Contribution)` method, use whatever it does expose to seed it (its shape is not covered by this plan's grounding research, which focused on `CouchDbContributionSource` since that is what the live endpoint uses).

- [ ] **Step 2: Run to verify it fails to compile**

Run: `./gradlew :connector:extensions:benefits-privacy:test --tests ReleaseRequestResourceTest`
Expected: compile failure.

- [ ] **Step 3: Implement `ReleaseRequestResource`**

```java
package org.benefits.policy;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.List;

@Path("/")
public class ReleaseRequestResource {

    private final ContributionSource contributions;
    private final ReleaseRequestStore requestStore;
    private final ReleaseArtifactStore artifactStore;
    private final BudgetAccountant budgetAccountant;
    private final NoiseSource noiseSource;

    public ReleaseRequestResource(ContributionSource contributions, ReleaseRequestStore requestStore,
                                   ReleaseArtifactStore artifactStore, BudgetAccountant budgetAccountant,
                                   NoiseSource noiseSource) {
        this.contributions = contributions;
        this.requestStore = requestStore;
        this.artifactStore = artifactStore;
        this.budgetAccountant = budgetAccountant;
        this.noiseSource = noiseSource;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(CreateReleaseRequest body) {
        if (body.releaseRequestId() == null || body.releaseRequestId().isBlank()) {
            return Response.status(400).entity(new ErrorBody("releaseRequestId is required")).build();
        }
        var pipeline = new PrivacyPipeline(contributions, noiseSource, budgetAccountant);
        var query = new QuerySpec(body.siteId(), body.fields());
        var artifact = pipeline.compute(body.releaseRequestId(), query, body.k(), body.epsilon());
        if (artifact.isEmpty()) {
            return Response.status(409).entity(new ErrorBody("insufficient-budget")).build();
        }
        artifactStore.save(artifact.get());
        requestStore.save(new ReleaseRequest(body.releaseRequestId(), body.purpose(), body.recipientClass(),
                body.fields(), body.siteId(), Instant.now()));
        return Response.status(201).entity(new Created(body.releaseRequestId())).build();
    }

    public record CreateReleaseRequest(String releaseRequestId, String purpose, String recipientClass,
                                        List<String> fields, String siteId, int k, double epsilon) {
    }

    public record Created(String releaseRequestId) {
    }

    public record ErrorBody(String error) {
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :connector:extensions:benefits-privacy:test --tests ReleaseRequestResourceTest`
Expected: PASS.

- [ ] **Step 5: Wire it into `BenefitsPrivacyExtension`, feature-flagged, following `BenefitsAuditExtension`'s `PortMapping` pattern**

Add fields and a `buildReleaseRequestStore` method (mirroring Task 5/6's `buildArtifactStore`/`buildBudgetAccountant`), and register the resource in `initialize()`:

```java
    @Inject
    private WebService webService;
    @Inject
    private PortMappingRegistry portMappingRegistry;

    // ... existing fields, artifactStore ...

    @Override
    public void initialize(ServiceExtensionContext context) {
        var monitor = context.getMonitor();
        artifactStore = buildArtifactStore(context);
        pipelineService.registerFactory(new ProbeDataSourceFactory());
        pipelineService.registerFactory(new BenefitsAggregateDataSourceFactory(artifactStore));

        ruleBindingRegistry.bind(K_ANONYMITY_KEY, NEGOTIATION_SCOPE);
        ruleBindingRegistry.bind(EPSILON_BUDGET_KEY, NEGOTIATION_SCOPE);
        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class,
                K_ANONYMITY_KEY, new KAnonymityFunction(artifactStore, monitor));
        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class,
                EPSILON_BUDGET_KEY, new EpsilonBudgetFunction(artifactStore, monitor));

        monitor.info("BENEFITS privacy pipeline registered");

        if (Boolean.parseBoolean(context.getSetting("benefits.releaseRequests.enabled", "false"))) {
            var url = context.getSetting("benefits.couchdb.url", "http://localhost:5984");
            var database = context.getSetting("benefits.couchdb.database", "benefits");
            var user = context.getSetting("benefits.couchdb.user", "admin");
            var password = context.getSetting("benefits.couchdb.password", "");
            var contributions = new CouchDbContributionSource(url, database, user, password,
                    new com.fasterxml.jackson.databind.ObjectMapper());
            var releaseRequestStore = buildReleaseRequestStore(context);
            var resource = new ReleaseRequestResource(contributions, releaseRequestStore, artifactStore,
                    buildBudgetAccountant(context), new LaplaceNoiseSource(new java.security.SecureRandom()));

            var port = context.getSetting("web.http.releaseRequests.port", 19198);
            var path = context.getSetting("web.http.releaseRequests.path", "/release-requests");
            portMappingRegistry.register(new PortMapping("releaseRequests", port, path));
            webService.registerResource("releaseRequests", resource);
        }

        seed(context);
    }

    private ReleaseRequestStore buildReleaseRequestStore(ServiceExtensionContext context) {
        if (!Boolean.parseBoolean(context.getSetting("benefits.privacy.persistence.enabled", "false"))) {
            return new InMemoryReleaseRequestStore();
        }
        var url = context.getSetting("benefits.couchdb.url", "http://localhost:5984");
        var database = context.getSetting("benefits.couchdb.database", "benefits") + "-requests";
        var user = context.getSetting("benefits.couchdb.user", "admin");
        var password = context.getSetting("benefits.couchdb.password", "");
        return new CouchDbReleaseRequestStore(url, database, user, password, new com.fasterxml.jackson.databind.ObjectMapper());
    }
```

Add `import org.eclipse.edc.web.spi.WebService;`, `import org.eclipse.edc.web.spi.configuration.PortMapping;`, `import org.eclipse.edc.web.spi.configuration.PortMappingRegistry;` to this file's imports.

- [ ] **Step 5b: Register the new port with `tools/lib.sh`'s cleanup list**

`tools/lib.sh` line 9 hardcodes every port `stop_all` (used by every `tools/*.sh` probe script's `trap ... EXIT`) knows to free: `PORTS="19191 19192 19193 19194 19195 19196 19197 19291 29191 29192 29193 29194 29195 29196 29291"`. Port `19198` (the default `web.http.releaseRequests.port` above) is new and confirmed free against this exact list -- add it:

```bash
PORTS="19191 19192 19193 19194 19195 19196 19197 19198 19291 29191 29192 29193 29194 29195 29196 29291"
```

Without this, a probe script that starts a connector with the release-requests API enabled would leave port 19198 bound after the script exits, breaking the next run.

- [ ] **Step 6: Run the whole module's test suite to confirm no regression**

Run: `./gradlew :connector:extensions:benefits-privacy:test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/ReleaseRequestResource.java \
        connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/ReleaseRequestResourceTest.java \
        connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/BenefitsPrivacyExtension.java \
        tools/lib.sh
git commit -m "feat(benefits-privacy): wire POST /release-requests to real pipeline computation"
```

### Task 8: AuditResource real authentication

**Files:**
- Create: `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/SessionAuthenticator.java`
- Create: `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/CouchDbSessionAuthenticator.java`
- Create: `connector/extensions/benefits-audit/src/test/java/org/benefits/audit/CouchDbSessionAuthenticatorIntegrationTest.java`
- Modify: `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/AuditResource.java`
- Modify: `connector/extensions/benefits-audit/src/test/java/org/benefits/audit/AuditResourceTest.java`
- Modify: `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/BenefitsAuditExtension.java`

**Interfaces:**
- Consumes: nothing from earlier tasks (independent of Tasks 1-7).
- Produces: `AuditResource(AuditStore store, SessionAuthenticator authenticator)`, `forParticipant(String participantRef, String authorizationHeader)` now returns `jakarta.ws.rs.core.Response` (200 with the existing `AuditResponse` body, or 403), not a bare `AuditResponse`. Task 12 (`apps/participant-web`) and Task 14 (Flutter audit viewer) both call this endpoint and must send the participant's own CouchDB Basic-Auth header.

Today `AuditResource` trusts the `participantRef` path parameter outright -- its own doc comment says filtering by it "is the whole privacy boundary this endpoint provides." This closes that: the caller presents a Basic-Auth header, this resource asks CouchDB's own `/_session` endpoint who that is, and rejects unless the authenticated name matches the requested `participantRef`. `SessionAuthenticator` is an interface specifically so `AuditResourceTest` can inject a stub rather than requiring live CouchDB for resource-logic tests; `CouchDbSessionAuthenticator` is the real implementation, proven separately against live CouchDB.

- [ ] **Step 1: Write `SessionAuthenticator`**

```java
package org.benefits.audit;

/**
 * Resolves a caller-supplied Authorization header to the identity CouchDB itself
 * authenticated, or null if the header is missing, malformed, or CouchDB rejects it.
 */
public interface SessionAuthenticator {

    String authenticate(String authorizationHeader);
}
```

- [ ] **Step 2: Rewrite the failing `AuditResourceTest`** (this is the TDD step for this task: the existing file compiles today against the old 1-arg constructor and bare-`AuditResponse` return type, so rewriting it to the new shape IS the failing test)

```java
package org.benefits.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditResourceTest {

    private static final SessionAuthenticator ALWAYS_ALICE = header -> "alice";
    private static final SessionAuthenticator ALWAYS_MALLORY = header -> "mallory";
    private static final SessionAuthenticator ALWAYS_UNAUTHENTICATED = header -> null;

    @Test
    void returnsOnlyEntriesForTheRequestedParticipantWithAProofPerEntryAndTheFullEpochChain() {
        var store = new InMemoryAuditStore();
        var sealer = new EpochSealer(store, 2);
        sealer.record(new AuditEntry(-1, null, "t1", "negotiation-finalized", "alice", "asset-1", "purpose", "plain-a"));
        sealer.record(new AuditEntry(-1, null, "t2", "negotiation-finalized", "bob", "asset-2", "purpose", "plain-b"));
        sealer.record(new AuditEntry(-1, null, "t3", "negotiation-finalized", "alice", "asset-3", "purpose", "plain-c"));

        var resource = new AuditResource(store, ALWAYS_ALICE);
        var httpResponse = resource.forParticipant("alice", "Basic irrelevant");
        assertThat(httpResponse.getStatus()).isEqualTo(200);
        var response = (AuditResource.AuditResponse) httpResponse.getEntity();

        assertThat(response.entries()).hasSize(2);
        assertThat(response.entries()).allMatch(e -> e.actor().equals("alice"));
        assertThat(response.proofs()).hasSize(1);
        assertThat(response.epochs()).hasSize(1);

        var sealedEntry = response.entries().stream().filter(e -> e.epoch() != null).findFirst().orElseThrow();
        var proof = response.proofs().get(sealedEntry.seq());
        var leaf = AuditHashing.leafHash(sealedEntry);
        var root = response.epochs().get(0).merkleRoot();
        assertThat(MerkleTree.verify(leaf, proof, root)).isTrue();
    }

    @Test
    void unsealedEntriesHaveNoProof() {
        var store = new InMemoryAuditStore();
        var sealer = new EpochSealer(store, 100);
        sealer.record(new AuditEntry(-1, null, "t1", "negotiation-finalized", "alice", "asset-1", "purpose", "plain"));

        var resource = new AuditResource(store, ALWAYS_ALICE);
        var httpResponse = resource.forParticipant("alice", "Basic irrelevant");
        var response = (AuditResource.AuditResponse) httpResponse.getEntity();

        assertThat(response.entries()).hasSize(1);
        assertThat(response.proofs()).isEmpty();
    }

    @Test
    void rejectsARequesterWhoseAuthenticatedIdentityDoesNotMatchTheRequestedParticipant() {
        var store = new InMemoryAuditStore();
        var resource = new AuditResource(store, ALWAYS_MALLORY);

        var httpResponse = resource.forParticipant("alice", "Basic irrelevant");

        assertThat(httpResponse.getStatus()).isEqualTo(403);
    }

    @Test
    void rejectsAMissingOrInvalidAuthorizationHeader() {
        var store = new InMemoryAuditStore();
        var resource = new AuditResource(store, ALWAYS_UNAUTHENTICATED);

        var httpResponse = resource.forParticipant("alice", null);

        assertThat(httpResponse.getStatus()).isEqualTo(403);
    }
}
```

- [ ] **Step 3: Run to verify it fails to compile**

Run: `./gradlew :connector:extensions:benefits-audit:test --tests AuditResourceTest`
Expected: compile failure (2-arg constructor and `Response` return type do not exist yet).

- [ ] **Step 4: Implement the new `AuditResource`**

```java
package org.benefits.audit;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A participant is not a dataspace-protocol consumer, so this is a plain read-only
 * resource registered directly against EDC's WebService, not a data plane transfer
 * (spec section 7). The caller's Authorization header is validated against CouchDB's
 * own /_session endpoint (SessionAuthenticator); the authenticated identity must
 * equal the requested participantRef, or this returns 403. Filtering entries by that
 * same participantRef is the second half of the privacy boundary: even an
 * authenticated participant only ever sees their own entries and the public epoch
 * chain, never anyone else's entries.
 */
@Path("/{participantRef}")
public class AuditResource {

    private final AuditStore store;
    private final SessionAuthenticator authenticator;

    public AuditResource(AuditStore store, SessionAuthenticator authenticator) {
        this.store = store;
        this.authenticator = authenticator;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response forParticipant(@PathParam("participantRef") String participantRef,
                                    @HeaderParam("Authorization") String authorizationHeader) {
        var authenticated = authenticator.authenticate(authorizationHeader);
        if (authenticated == null || !authenticated.equals(participantRef)) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }

        var entries = store.entriesFor(participantRef);
        var epochs = store.epochChain();

        Map<String, List<String>> leavesByEpoch = new HashMap<>();
        for (var epoch : epochs) {
            leavesByEpoch.put(epoch.epochId(), leafHashesFor(epoch.epochId()));
        }

        Map<Long, MerkleProof> proofs = new HashMap<>();
        for (var entry : entries) {
            if (entry.epoch() == null) {
                continue;
            }
            var leaves = leavesByEpoch.get(entry.epoch());
            var index = indexOfSeq(entry.epoch(), entry.seq());
            proofs.put(entry.seq(), MerkleTree.proof(leaves, index));
        }

        return Response.ok(new AuditResponse(entries, proofs, epochs)).build();
    }

    private List<String> leafHashesFor(String epochId) {
        return store.entriesInEpoch(epochId).stream().map(AuditHashing::leafHash).toList();
    }

    private int indexOfSeq(String epochId, long seq) {
        var members = store.entriesInEpoch(epochId);
        for (var i = 0; i < members.size(); i++) {
            if (members.get(i).seq() == seq) {
                return i;
            }
        }
        throw new IllegalStateException("seq " + seq + " not found among members of epoch " + epochId);
    }

    public record AuditResponse(List<AuditEntry> entries, Map<Long, MerkleProof> proofs, List<Epoch> epochs) {
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew :connector:extensions:benefits-audit:test --tests AuditResourceTest`
Expected: PASS.

- [ ] **Step 6: Write the failing `CouchDbSessionAuthenticatorIntegrationTest`**

```java
package org.benefits.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class CouchDbSessionAuthenticatorIntegrationTest {

    private final CouchDbSessionAuthenticator authenticator =
            new CouchDbSessionAuthenticator("http://localhost:5984", new ObjectMapper());

    @Test
    void resolvesTheAuthenticatedAdminUsername() {
        var header = "Basic " + Base64.getEncoder().encodeToString("admin:benefits".getBytes());
        assertThat(authenticator.authenticate(header)).isEqualTo("admin");
    }

    @Test
    void returnsNullForAMissingHeader() {
        assertThat(authenticator.authenticate(null)).isNull();
    }

    @Test
    void returnsNullForBadCredentials() {
        var header = "Basic " + Base64.getEncoder().encodeToString("admin:wrong-password".getBytes());
        assertThat(authenticator.authenticate(header)).isNull();
    }
}
```

- [ ] **Step 7: Run to verify it fails to compile**

Run: `./gradlew :connector:extensions:benefits-audit:test --tests CouchDbSessionAuthenticatorIntegrationTest`
Expected: compile failure.

- [ ] **Step 8: Implement `CouchDbSessionAuthenticator`**

```java
package org.benefits.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.edc.spi.EdcException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Validates a caller-supplied Basic-Auth header against CouchDB's own /_session
 * endpoint and returns the authenticated username, or null if the header is missing
 * or CouchDB rejects it. Reuses CouchDB's own user store rather than a separate
 * identity provider, the same reasoning as the participant-identity write check in
 * consent-couchdb's build-design-doc.ts.
 */
public class CouchDbSessionAuthenticator implements SessionAuthenticator {

    private final String baseUrl;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();

    public CouchDbSessionAuthenticator(String baseUrl, ObjectMapper mapper) {
        this.baseUrl = baseUrl;
        this.mapper = mapper;
    }

    @Override
    public String authenticate(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return null;
        }
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/_session"))
                    .header("authorization", authorizationHeader).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            var body = mapper.readTree(response.body());
            var name = body.path("userCtx").path("name");
            return name.isTextual() ? name.asText() : null;
        } catch (IOException e) {
            throw new EdcException("CouchDB session check failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdcException("CouchDB session check interrupted", e);
        }
    }
}
```

- [ ] **Step 9: Run to verify it passes**

Run: `./gradlew :connector:extensions:benefits-audit:test --tests CouchDbSessionAuthenticatorIntegrationTest`
Expected: PASS (requires live CouchDB).

- [ ] **Step 10: Wire the authenticator into `BenefitsAuditExtension`**

Change the one line that constructs `AuditResource`:

```java
        var port = context.getSetting("web.http.audit.port", 19197);
        var path = context.getSetting("web.http.audit.path", "/audit");
        portMappingRegistry.register(new PortMapping("audit", port, path));
        var couchdbUrl = context.getSetting("benefits.couchdb.url", "http://localhost:5984");
        var authenticator = new CouchDbSessionAuthenticator(couchdbUrl, new ObjectMapper());
        webService.registerResource("audit", new AuditResource(store, authenticator));
        monitor.info("BENEFITS audit chain registered, epochSize=" + epochSize);
```

Add `import com.fasterxml.jackson.databind.ObjectMapper;` to this file's imports (it is not already imported at the top level -- `buildStore` currently constructs one inline as `new ObjectMapper()` with the fully qualified... check: the existing file's `buildStore` method already uses `new ObjectMapper()` unqualified per the grounding research excerpt, meaning the import already exists. Confirm by reading the file's current import list before editing; add the import only if it is missing).

- [ ] **Step 11: Run the whole module's test suite to confirm no regression**

Run: `./gradlew :connector:extensions:benefits-audit:test`
Expected: PASS.

- [ ] **Step 12: Commit**

```bash
git add connector/extensions/benefits-audit/src/main/java/org/benefits/audit/SessionAuthenticator.java \
        connector/extensions/benefits-audit/src/main/java/org/benefits/audit/CouchDbSessionAuthenticator.java \
        connector/extensions/benefits-audit/src/test/java/org/benefits/audit/CouchDbSessionAuthenticatorIntegrationTest.java \
        connector/extensions/benefits-audit/src/main/java/org/benefits/audit/AuditResource.java \
        connector/extensions/benefits-audit/src/test/java/org/benefits/audit/AuditResourceTest.java \
        connector/extensions/benefits-audit/src/main/java/org/benefits/audit/BenefitsAuditExtension.java
git commit -m "feat(benefits-audit): require the caller's own CouchDB identity on AuditResource"
```

### Task 9: StewardResource (review, co-sign, veto, appeal, provisioning)

**Files:**
- Create: `connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/StewardAuthenticator.java`
- Create: `connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/CouchDbStewardAuthenticator.java`
- Create: `connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/CouchDbUserProvisioner.java`
- Create: `connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/StewardResource.java`
- Create: `connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/StewardResourceTest.java`
- Create: `connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/CouchDbStewardAuthenticatorIntegrationTest.java`
- Create: `connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/StewardResourceConcurrencyIntegrationTest.java`
- Modify: `connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/BenefitsPrivacyExtension.java`
- Modify: `tools/lib.sh`

**Interfaces:**
- Consumes: Task 2/3's `StewardAuthorizationStore` and its four `append*` methods (injected via EDC's cross-extension `@Provider`/`@Inject`, not a Gradle dependency -- `benefits-privacy` already compiles against `benefits-policy`'s types via `implementation(project(":connector:extensions:benefits-policy"))`); Task 4's `ReleaseRequest`/`ReleaseRequestStore`.
- Produces: a live `StewardResource` at `GET /steward/queue`, `POST /steward/{requestId}/review`, `POST /steward/{requestId}/co-sign`, `POST /steward/{requestId}/veto`, `POST /steward/{requestId}/appeal`, `POST /steward/participants/{participantRef}/provision`. Task 15 (`apps/steward-portal`) is the client for all six.

**Ruling: this resource lives in `benefits-privacy`, not `benefits-policy`.** `StewardResource`'s queue joins `ReleaseRequestStore` (only visible in `benefits-privacy`, where it was created, Task 4) with `StewardAuthorizationStore` (`benefits-policy`). `benefits-privacy` depends on `benefits-policy`; the reverse dependency does not exist and this plan does not add it. Only `benefits-privacy` can see both types, so this resource can only live there.

**Ruling: a separate `StewardAuthenticator`, not a reuse of Task 8's `SessionAuthenticator`.** `SessionAuthenticator` lives in `benefits-audit`'s package (`org.benefits.audit`); `benefits-privacy` has no dependency on `benefits-audit` (confirmed: `benefits-audit` depends on `benefits-policy`, not the other way, and `benefits-privacy` depends only on `benefits-policy`). Sharing the interface across modules would need a new dependency edge for one interface -- not worth it. `StewardAuthenticator` here does the same `/_session` HTTP call as Task 8's authenticator, plus one more check Task 8's never needed: the authenticated user's `roles` must include `"steward"` (spec §5.5's Global Constraint). A provisioned participant's valid CouchDB credentials must NOT pass this check.

**Ruling: `datasetHash` is caller-supplied on every review/co-sign/veto request body, not computed here.** No code anywhere in this codebase computes a dataset hash (`run-case.sh`'s seed data always hardcodes one, e.g. `"hash-" + case`); inventing a hashing scheme is out of this plan's scope. `StewardAuthorizationStore.append*`'s own contract (Task 2) already tolerates this: the passed `datasetHash` is used only if no authorization exists yet for the release request id, and ignored (the original is kept) on every later append.

- [ ] **Step 1: Write `StewardAuthenticator` and `CouchDbUserProvisioner`**

`StewardAuthenticator.java`:
```java
package org.benefits.policy;

/**
 * Resolves a caller-supplied Authorization header to the authenticated steward's
 * username, or null if the header is missing, CouchDB rejects it, or the
 * authenticated user's roles do not include "steward" -- a provisioned
 * participant's own valid credentials must not pass this check.
 */
public interface StewardAuthenticator {

    String authenticateSteward(String authorizationHeader);
}
```

`CouchDbUserProvisioner.java`:
```java
package org.benefits.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.edc.spi.EdcException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.List;

/**
 * Creates a CouchDB user via the standard /_users/org.couchdb.user:<name> PUT
 * convention, authenticated as a CouchDB admin. 201 (created) and 409 (already
 * exists, e.g. re-provisioning) are both treated as success -- provisioning is
 * idempotent by design, matching the reasoning in Task 1's ensureCouchDbUser test
 * helper.
 */
public class CouchDbUserProvisioner {

    private final String baseUrl;
    private final String adminAuthHeader;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();

    public CouchDbUserProvisioner(String baseUrl, String adminUser, String adminPassword, ObjectMapper mapper) {
        this.baseUrl = baseUrl;
        this.adminAuthHeader = "Basic " + Base64.getEncoder().encodeToString((adminUser + ":" + adminPassword).getBytes());
        this.mapper = mapper;
    }

    public void provision(String username, String password, List<String> roles) {
        var body = mapper.createObjectNode();
        body.put("name", username);
        body.put("password", password);
        var rolesArray = body.putArray("roles");
        roles.forEach(rolesArray::add);
        body.put("type", "user");
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/_users/org.couchdb.user:" + username))
                    .header("authorization", adminAuthHeader).header("content-type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 201 && response.statusCode() != 409) {
                throw new EdcException("failed to provision CouchDB user " + username + ": "
                        + response.statusCode() + " " + response.body());
            }
        } catch (IOException e) {
            throw new EdcException("failed to provision CouchDB user " + username, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdcException("interrupted provisioning CouchDB user " + username, e);
        }
    }
}
```

`CouchDbStewardAuthenticator.java`:
```java
package org.benefits.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.edc.spi.EdcException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class CouchDbStewardAuthenticator implements StewardAuthenticator {

    private final String baseUrl;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();

    public CouchDbStewardAuthenticator(String baseUrl, ObjectMapper mapper) {
        this.baseUrl = baseUrl;
        this.mapper = mapper;
    }

    @Override
    public String authenticateSteward(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return null;
        }
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/_session"))
                    .header("authorization", authorizationHeader).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            var userCtx = mapper.readTree(response.body()).path("userCtx");
            var name = userCtx.path("name");
            var isSteward = false;
            for (var role : userCtx.path("roles")) {
                if ("steward".equals(role.asText())) {
                    isSteward = true;
                    break;
                }
            }
            return name.isTextual() && isSteward ? name.asText() : null;
        } catch (IOException e) {
            throw new EdcException("CouchDB session check failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdcException("CouchDB session check interrupted", e);
        }
    }
}
```

- [ ] **Step 2: Write the failing `StewardResourceTest`** (stub authenticator, in-memory stores, no live CouchDB)

```java
package org.benefits.policy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StewardResourceTest {

    private static final StewardAuthenticator ALWAYS_STEWARD_S1 = header -> "s-1";
    private static final StewardAuthenticator ALWAYS_UNAUTHENTICATED = header -> null;

    private StewardAuthorizationStore authorizationStore;
    private ReleaseRequestStore requestStore;
    private StewardResource resource;

    @BeforeEach
    void setUp() {
        authorizationStore = new InMemoryStewardAuthorizationStore();
        requestStore = new InMemoryReleaseRequestStore();
        resource = new StewardResource(authorizationStore, requestStore, ALWAYS_STEWARD_S1, null);
        requestStore.save(new ReleaseRequest("rr-1", "service-improvement", "statistics",
                List.of("service.satisfaction"), "athens", Instant.parse("2026-09-01T00:00:00Z")));
    }

    @Test
    void queueJoinsRequestsWithTheirAuthorizationState() {
        authorizationStore.appendSignature("rr-1", "hash-1",
                new StewardSignature("s-1", "sig-s-1", Instant.now()));

        var response = resource.queue("Basic irrelevant");

        assertThat(response.getStatus()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        var entries = (List<StewardResource.QueueEntry>) response.getEntity();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).request().releaseRequestId()).isEqualTo("rr-1");
        assertThat(entries.get(0).authorization().signatures()).hasSize(1);
    }

    @Test
    void queueRejectsAnUnauthenticatedCaller() {
        var unauthenticated = new StewardResource(authorizationStore, requestStore, ALWAYS_UNAUTHENTICATED, null);
        var response = unauthenticated.queue("Basic irrelevant");
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void coSignAppendsUnderTheAuthenticatedStewardsIdentityNotAClientSuppliedOne() {
        resource.coSign("rr-1", "Basic irrelevant", new StewardResource.CoSignBody("hash-1", "sig-anything"));

        var authorization = authorizationStore.findByReleaseRequestId("rr-1");
        assertThat(authorization.signatures()).hasSize(1);
        assertThat(authorization.signatures().get(0).stewardId()).isEqualTo("s-1");
    }

    @Test
    void vetoThenAppealSucceeds() {
        resource.veto("rr-1", "Basic irrelevant", new StewardResource.VetoBody("hash-1", "field set too broad"));
        var appealResponse = resource.appeal("rr-1", "Basic irrelevant",
                new StewardResource.AppealBody("veto was mistaken", "s-1"));

        assertThat(appealResponse.getStatus()).isEqualTo(201);
        assertThat(authorizationStore.findByReleaseRequestId("rr-1").appeals()).hasSize(1);
    }

    @Test
    void appealAgainstANonexistentVetoReturnsConflict() {
        var response = resource.appeal("rr-1", "Basic irrelevant",
                new StewardResource.AppealBody("no such veto", "s-nobody"));
        assertThat(response.getStatus()).isEqualTo(409);
    }
}
```

- [ ] **Step 3: Run to verify it fails to compile**

Run: `./gradlew :connector:extensions:benefits-privacy:test --tests StewardResourceTest`
Expected: compile failure.

- [ ] **Step 4: Implement `StewardResource`**

```java
package org.benefits.policy;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.edc.spi.EdcException;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

@Path("/")
public class StewardResource {

    private final StewardAuthorizationStore authorizationStore;
    private final ReleaseRequestStore requestStore;
    private final StewardAuthenticator authenticator;
    private final CouchDbUserProvisioner provisioner;

    public StewardResource(StewardAuthorizationStore authorizationStore, ReleaseRequestStore requestStore,
                            StewardAuthenticator authenticator, CouchDbUserProvisioner provisioner) {
        this.authorizationStore = authorizationStore;
        this.requestStore = requestStore;
        this.authenticator = authenticator;
        this.provisioner = provisioner;
    }

    @GET
    @Path("/queue")
    @Produces(MediaType.APPLICATION_JSON)
    public Response queue(@HeaderParam("Authorization") String authorizationHeader) {
        if (authenticator.authenticateSteward(authorizationHeader) == null) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        var entries = requestStore.all().stream()
                .map(r -> new QueueEntry(r, authorizationStore.findByReleaseRequestId(r.releaseRequestId())))
                .toList();
        return Response.ok(entries).build();
    }

    @POST
    @Path("/{requestId}/review")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response review(@PathParam("requestId") String requestId,
                            @HeaderParam("Authorization") String authorizationHeader, ReviewBody body) {
        var steward = authenticator.authenticateSteward(authorizationHeader);
        if (steward == null) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        authorizationStore.appendReview(requestId, body.datasetHash(),
                new StewardReview(steward, body.rubricScore(), body.notes(), Instant.now()));
        return Response.status(201).build();
    }

    @POST
    @Path("/{requestId}/co-sign")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response coSign(@PathParam("requestId") String requestId,
                            @HeaderParam("Authorization") String authorizationHeader, CoSignBody body) {
        var steward = authenticator.authenticateSteward(authorizationHeader);
        if (steward == null) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        authorizationStore.appendSignature(requestId, body.datasetHash(),
                new StewardSignature(steward, body.signature(), Instant.now()));
        return Response.status(201).build();
    }

    @POST
    @Path("/{requestId}/veto")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response veto(@PathParam("requestId") String requestId,
                          @HeaderParam("Authorization") String authorizationHeader, VetoBody body) {
        var steward = authenticator.authenticateSteward(authorizationHeader);
        if (steward == null) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        authorizationStore.appendVeto(requestId, body.datasetHash(),
                new StewardVeto(steward, body.reason(), Instant.now()));
        return Response.status(201).build();
    }

    @POST
    @Path("/{requestId}/appeal")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response appeal(@PathParam("requestId") String requestId,
                            @HeaderParam("Authorization") String authorizationHeader, AppealBody body) {
        var steward = authenticator.authenticateSteward(authorizationHeader);
        if (steward == null) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        try {
            authorizationStore.appendAppeal(requestId, new StewardAppeal(steward, body.reason(), body.vetoStewardId(), Instant.now()));
        } catch (EdcException e) {
            return Response.status(409).entity(new ErrorBody(e.getMessage())).build();
        }
        return Response.status(201).build();
    }

    @POST
    @Path("/participants/{participantRef}/provision")
    @Produces(MediaType.APPLICATION_JSON)
    public Response provisionParticipant(@PathParam("participantRef") String participantRef,
                                          @HeaderParam("Authorization") String authorizationHeader) {
        var steward = authenticator.authenticateSteward(authorizationHeader);
        if (steward == null) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        var password = generatePassword();
        provisioner.provision(participantRef, password, List.of());
        return Response.status(201).entity(new ProvisionedCredentials(participantRef, password)).build();
    }

    private static String generatePassword() {
        var bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record QueueEntry(ReleaseRequest request, StewardAuthorization authorization) {
    }

    public record ReviewBody(String datasetHash, int rubricScore, String notes) {
    }

    public record CoSignBody(String datasetHash, String signature) {
    }

    public record VetoBody(String datasetHash, String reason) {
    }

    public record AppealBody(String reason, String vetoStewardId) {
    }

    public record ProvisionedCredentials(String participantRef, String password) {
    }

    public record ErrorBody(String error) {
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew :connector:extensions:benefits-privacy:test --tests StewardResourceTest`
Expected: PASS (the `provisionParticipant` path is untested here since these tests pass `null` for `provisioner` deliberately -- none of these five tests calls it).

- [ ] **Step 5b: Write the failing `CouchDbStewardAuthenticatorIntegrationTest`**

Every test above uses a stub `StewardAuthenticator`; nothing yet proves `CouchDbStewardAuthenticator` itself enforces the `"steward"` role against real CouchDB -- the exact promise Global Constraints makes ("a provisioned participant's valid credentials must NOT pass this check"). This mirrors Task 8's `CouchDbSessionAuthenticatorIntegrationTest` pattern:

```java
package org.benefits.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class CouchDbStewardAuthenticatorIntegrationTest {

    private static final String ADMIN_AUTH = "Basic " + Base64.getEncoder().encodeToString("admin:benefits".getBytes());
    private final CouchDbStewardAuthenticator authenticator =
            new CouchDbStewardAuthenticator("http://localhost:5984", new ObjectMapper());

    @BeforeAll
    static void provisionUsers() throws IOException, InterruptedException {
        provisionUser("s-integration-test", "steward-pw", "[\"steward\"]");
        provisionUser("p-integration-test", "participant-pw", "[]");
    }

    private static void provisionUser(String name, String password, String rolesJson) throws IOException, InterruptedException {
        var client = HttpClient.newHttpClient();
        var body = "{\"name\":\"" + name + "\",\"password\":\"" + password + "\",\"roles\":" + rolesJson + ",\"type\":\"user\"}";
        var request = HttpRequest.newBuilder(URI.create("http://localhost:5984/_users/org.couchdb.user:" + name))
                .header("authorization", ADMIN_AUTH).header("content-type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 201 && response.statusCode() != 409) {
            throw new IllegalStateException("failed to provision test user " + name + ": " + response.statusCode());
        }
    }

    @Test
    void authenticatesAUserWithTheStewardRole() {
        var header = "Basic " + Base64.getEncoder().encodeToString("s-integration-test:steward-pw".getBytes());
        assertThat(authenticator.authenticateSteward(header)).isEqualTo("s-integration-test");
    }

    @Test
    void rejectsAValidUserWithoutTheStewardRole() {
        var header = "Basic " + Base64.getEncoder().encodeToString("p-integration-test:participant-pw".getBytes());
        assertThat(authenticator.authenticateSteward(header)).isNull();
    }

    @Test
    void rejectsAMissingHeader() {
        assertThat(authenticator.authenticateSteward(null)).isNull();
    }
}
```

Run: `./gradlew :connector:extensions:benefits-privacy:test --tests CouchDbStewardAuthenticatorIntegrationTest`
Expected: fails to compile before `CouchDbStewardAuthenticator` exists (Step 1 already wrote it in this task, so if Steps are followed in order this test should already compile and simply need running); PASS once run (requires live CouchDB). The middle test, `rejectsAValidUserWithoutTheStewardRole`, is the one that would have caught a missing role check entirely -- confirm it fails first if you temporarily remove the role check from Step 1's `CouchDbStewardAuthenticator`, to prove this test is load-bearing, then restore the check.

- [ ] **Step 6: Write the failing Gate 2 concurrency test, against real CouchDB**

```java
package org.benefits.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate 2: two genuinely concurrent HTTP-shaped calls (two threads calling the
 * resource's coSign method, backed by real CouchDB) must both persist -- this is
 * the direct proof that the atomic-append fix (Task 2, Task 3) actually closed
 * StewardAuthorizationStore's own documented replace-semantics race, not just a
 * unit-level ConcurrentHashMap test.
 */
class StewardResourceConcurrencyIntegrationTest {

    @Test
    void twoConcurrentCoSignsBothPersistToCouchDb() throws InterruptedException {
        var id = "rr-http-concurrent-" + System.nanoTime();
        var authorizationStore = new CouchDbStewardAuthorizationStore("http://localhost:5984",
                "benefits-steward-test", "admin", "benefits", new ObjectMapper());
        var requestStore = new InMemoryReleaseRequestStore();
        StewardAuthenticator authenticatorS1 = header -> "s-1";
        StewardAuthenticator authenticatorS2 = header -> "s-2";
        var resourceAsS1 = new StewardResource(authorizationStore, requestStore, authenticatorS1, null);
        var resourceAsS2 = new StewardResource(authorizationStore, requestStore, authenticatorS2, null);

        var pool = Executors.newFixedThreadPool(2);
        var go = new CountDownLatch(1);
        pool.submit(() -> {
            try {
                go.await();
                resourceAsS1.coSign(id, "Basic irrelevant", new StewardResource.CoSignBody("hash-http", "sig-s-1"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        pool.submit(() -> {
            try {
                go.await();
                resourceAsS2.coSign(id, "Basic irrelevant", new StewardResource.CoSignBody("hash-http", "sig-s-2"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();

        var authorization = authorizationStore.findByReleaseRequestId(id);
        assertThat(authorization).isNotNull();
        assertThat(authorization.distinctStewardCount()).isEqualTo(2);
    }
}
```

- [ ] **Step 7: Run to verify it fails, then run again after implementation to verify it passes**

Run: `./gradlew :connector:extensions:benefits-privacy:test --tests StewardResourceConcurrencyIntegrationTest`
Expected: fails to compile before Step 4's implementation exists; PASS afterward (requires live CouchDB). If it passes only intermittently across repeated runs, that is a real defect in Task 3's retry loop, not test flakiness to tolerate.

- [ ] **Step 8: Wire `StewardResource` into `BenefitsPrivacyExtension`, feature-flagged**

```java
    @Inject
    private StewardAuthorizationStore stewardAuthorizationStore;

    // inside initialize(), after the release-requests block from Task 7:

        if (Boolean.parseBoolean(context.getSetting("benefits.stewardApi.enabled", "false"))) {
            var couchdbUrl = context.getSetting("benefits.couchdb.url", "http://localhost:5984");
            var couchUser = context.getSetting("benefits.couchdb.user", "admin");
            var couchPassword = context.getSetting("benefits.couchdb.password", "");
            var stewardAuthenticator = new CouchDbStewardAuthenticator(couchdbUrl, new com.fasterxml.jackson.databind.ObjectMapper());
            var provisioner = new CouchDbUserProvisioner(couchdbUrl, couchUser, couchPassword,
                    new com.fasterxml.jackson.databind.ObjectMapper());
            var stewardResource = new StewardResource(stewardAuthorizationStore, buildReleaseRequestStore(context),
                    stewardAuthenticator, provisioner);

            var stewardPort = context.getSetting("web.http.steward.port", 19199);
            var stewardPath = context.getSetting("web.http.steward.path", "/steward");
            portMappingRegistry.register(new PortMapping("steward", stewardPort, stewardPath));
            webService.registerResource("steward", stewardResource);
        }
```

`buildReleaseRequestStore(context)` is Task 7's existing method, called a second time here -- constructing a second `ReleaseRequestStore` instance for the same underlying CouchDB database (when persistence is enabled) is fine, since neither instance holds in-process state beyond an HTTP client; both read and write the same documents.

- [ ] **Step 8b: Register the new port with `tools/lib.sh`'s cleanup list**

Port `19199` (the default `web.http.steward.port` above) is new. `tools/lib.sh`'s `PORTS` line was already updated once in Task 7 to add `19198`; update it again here to add `19199`:

```bash
PORTS="19191 19192 19193 19194 19195 19196 19197 19198 19199 19291 29191 29192 29193 29194 29195 29196 29291"
```

- [ ] **Step 9: Run the whole module's test suite to confirm no regression**

Run: `./gradlew :connector:extensions:benefits-privacy:test`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/StewardAuthenticator.java \
        connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/CouchDbStewardAuthenticator.java \
        connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/CouchDbUserProvisioner.java \
        connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/StewardResource.java \
        connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/StewardResourceTest.java \
        connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/CouchDbStewardAuthenticatorIntegrationTest.java \
        connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/StewardResourceConcurrencyIntegrationTest.java \
        connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/BenefitsPrivacyExtension.java \
        tools/lib.sh
git commit -m "feat(benefits-privacy): add StewardResource (review, co-sign, veto, appeal, provisioning)"
```

### Task 10: tools/provision-steward.sh (steward bootstrap)

**Files:**
- Create: `tools/provision-steward.sh`

**Interfaces:**
- Consumes: nothing from earlier tasks (a standalone operational script, like every other file in `tools/`).
- Produces: a way to create the first steward CouchDB user, closing the chicken-and-egg gap `StewardResource`'s provisioning endpoint (Task 9) cannot close by itself -- provisioning a *participant* requires an authenticated steward already; provisioning the *first* steward cannot go through that same endpoint, since no steward yet exists to authenticate the call. This satisfies spec §5.5's "a steward-side sibling, provisioning a steward's own CouchDB user the same way" -- this script is that sibling, run once per steward by whoever administers the deployment, using CouchDB admin credentials directly rather than a steward's.

This is spec §5.5's provisioning path realized as an operational script rather than an HTTP endpoint, mirroring the existing `tools/*.sh` convention (`lib.sh` sourcing, direct `curl` against CouchDB) rather than inventing a new pattern.

- [ ] **Step 1: Read `tools/lib.sh`** to confirm the exact variables and helper functions it exports (`$ROOT`, any existing `curl`-wrapping function) before writing this script, so it follows the same conventions as its siblings rather than guessing at them.

- [ ] **Step 2: Write the script**

```bash
#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib.sh"

STEWARD_ID=${1:?usage: provision-steward.sh <steward-id> [password]}
PASSWORD=${2:-$(openssl rand -base64 24)}
COUCHDB_URL=${COUCHDB_URL:-http://localhost:5984}
COUCHDB_ADMIN_USER=${COUCHDB_ADMIN_USER:-admin}
COUCHDB_ADMIN_PASSWORD=${COUCHDB_ADMIN_PASSWORD:-benefits}

STATUS=$(curl -s -o /tmp/provision-steward-response.json -w "%{http_code}" \
  -u "$COUCHDB_ADMIN_USER:$COUCHDB_ADMIN_PASSWORD" \
  -X PUT "$COUCHDB_URL/_users/org.couchdb.user:$STEWARD_ID" \
  -H "content-type: application/json" \
  -d "{\"name\":\"$STEWARD_ID\",\"password\":\"$PASSWORD\",\"roles\":[\"steward\"],\"type\":\"user\"}")

if [ "$STATUS" != "201" ] && [ "$STATUS" != "409" ]; then
  echo "failed to provision steward $STEWARD_ID: HTTP $STATUS" >&2
  cat /tmp/provision-steward-response.json >&2
  exit 1
fi

echo "steward provisioned: $STEWARD_ID"
echo "password: $PASSWORD"
echo "(re-run with the same steward-id and an explicit password argument to rotate it; CouchDB's PUT overwrites the existing user document)"
```

`openssl rand -base64 24` for a default random password follows the same "no plaintext secret checked in" spirit as the rest of this repo's tooling; a caller who wants a specific password passes it as the second argument.

- [ ] **Step 3: Make it executable and smoke-test it against a running CouchDB**

```bash
chmod +x tools/provision-steward.sh
./tools/provision-steward.sh s-smoke-test
```
Expected: prints "steward provisioned: s-smoke-test" and a password (requires `docker compose -f deploy/docker-compose.yml up -d` per README, if not already running).

Verify the role landed correctly:
```bash
curl -s -u admin:benefits http://localhost:5984/_users/org.couchdb.user:s-smoke-test | python3 -c "import json,sys; print(json.load(sys.stdin)['roles'])"
```
Expected: `['steward']`.

- [ ] **Step 4: Commit**

```bash
git add tools/provision-steward.sh
git commit -m "feat(tools): add steward CouchDB user bootstrap script"
```

### Task 11: packages/consent-web-client

**Files:**
- Create: `packages/consent-web-client/package.json`
- Create: `packages/consent-web-client/tsconfig.json`
- Create: `packages/consent-web-client/src/pouchdb.ts`
- Create: `packages/consent-web-client/src/device.ts`
- Create: `packages/consent-web-client/src/sync.ts`
- Create: `packages/consent-web-client/src/index.ts`
- Create: `packages/consent-web-client/test/device.test.ts`
- Create: `packages/consent-web-client/test/sync.integration.test.ts`
- Create: `packages/consent-web-client/test/setup.ts`

**Interfaces:**
- Consumes: `@benefits/consent-core`'s `ConsentGrant`, `ContributionRecord` types (unchanged).
- Produces: `ParticipantClient` (renamed from phase 5's `Device` -- this is the real client, not a simulated one; spec §7 calls this out explicitly), `syncDevice`, `resolveConflicts`. Task 12 (`apps/participant-web`) imports and uses these directly.

This is `packages/device-sim`'s `device.ts`/`sync.ts` logic (already gate-proven in phase 5, including the C3 leaked-record fix and the identity-based conflict resolution fix) ported to browser persistence -- `pouchdb-adapter-idb` instead of `pouchdb-adapter-memory` is the only substantive change to the sync/device logic itself. **Not a dependency on `device-sim`**, which stays simulation-only per its own package comments (spec §7). This package also does not repeat `device-sim`'s parked packaging defect: `package.json`'s `"main"` points at a real, built `dist/index.js` from a real `src/index.ts`, so it is importable by name from `apps/participant-web` (Task 12) from the start.

- [ ] **Step 1: Verify the new npm dependencies against the real registry before using them**

Run: `npm view pouchdb-adapter-idb versions --json | tail -5` and `npm view fake-indexeddb versions --json | tail -5` (or check https://www.npmjs.com/package/pouchdb-adapter-idb and https://www.npmjs.com/package/fake-indexeddb directly). Confirm the exact latest `9.x` version of `pouchdb-adapter-idb` (matching the `9.0.0` pin every other `pouchdb-*` package in this repo uses) and the exact `@types/pouchdb-adapter-idb` version, and the latest `fake-indexeddb` major version, exist for real. Phase 5 caught a nonexistent `@types/pouchdb-adapter-memory@6.1.7` this exact way; do not skip this step. Use the confirmed real version numbers in Step 2, replacing any placeholder below that does not match what the registry actually returns.

- [ ] **Step 2: `package.json`**

```json
{
  "name": "@benefits/consent-web-client",
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
    "pouchdb-adapter-idb": "9.0.0",
    "pouchdb-adapter-http": "9.0.0",
    "pouchdb-replication": "9.0.0"
  },
  "devDependencies": {
    "@types/pouchdb-core": "7.0.15",
    "@types/pouchdb-adapter-idb": "6.1.7",
    "@types/pouchdb-adapter-http": "6.1.6",
    "@types/pouchdb-replication": "6.4.7",
    "fake-indexeddb": "6.0.0"
  }
}
```

Every version number above other than the ones already used elsewhere in this repo (`pouchdb-core`, `pouchdb-adapter-http`, `pouchdb-replication`, `@benefits/consent-core`) is a placeholder pending Step 1's real registry check -- correct any that Step 1 found to not exist.

- [ ] **Step 3: `tsconfig.json`** (browser target, NOT the root ES5-constrained one -- Global Constraints)

```json
{
  "compilerOptions": {
    "target": "ES2020",
    "lib": ["ES2020", "DOM"],
    "module": "ES2020",
    "moduleResolution": "bundler",
    "strict": true,
    "declaration": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "outDir": "dist"
  },
  "include": ["src"]
}
```

- [ ] **Step 4: `src/pouchdb.ts`**, `device-sim/src/pouchdb.ts` with the adapter swapped:

```typescript
import PouchDB from 'pouchdb-core';
import idbAdapter from 'pouchdb-adapter-idb';
import httpAdapter from 'pouchdb-adapter-http';
import replicationPlugin from 'pouchdb-replication';

PouchDB.plugin(idbAdapter);
PouchDB.plugin(httpAdapter);
PouchDB.plugin(replicationPlugin);

// The idb adapter is the real browser persistence layer -- this is the
// actual participant client, not a simulation (device-sim's memory
// adapter is deliberately test-only, spec section 3 of the phase 5
// design). IndexedDB survives page reloads within one browser profile,
// which is the property the privacy dashboard and offline outbox both
// depend on.
export function createLocalDb(name: string): PouchDB.Database {
  return new PouchDB(name, { adapter: 'idb' });
}

export function createRemoteDb(url: string): PouchDB.Database {
  return new PouchDB(url);
}
```

- [ ] **Step 5: `src/device.ts`**, `device-sim/src/device.ts` verbatim except the class name (`Device` to `ParticipantClient`, since this is the real client) and the import path (`./pouchdb`, unchanged as a relative import, now resolving to this package's own idb-backed `pouchdb.ts`):

```typescript
import type { ConsentGrant, ContributionRecord } from '@benefits/consent-core';
import { createLocalDb } from './pouchdb';

export class ParticipantClient {
  private readonly db: PouchDB.Database;
  private localGrants: ConsentGrant[] = [];
  private sentIds = new Set<string>();

  constructor(private readonly participantRef: string) {
    this.db = createLocalDb(`participant-${participantRef}`);
  }

  async grantConsent(grant: ConsentGrant): Promise<void> {
    this.localGrants.push(grant);
  }

  grants(): ConsentGrant[] {
    return this.localGrants;
  }

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

  async contribute(record: Omit<ContributionRecord, '_id'>): Promise<void> {
    await this.db.put({ ...record, _id: `contribution-${Date.now()}-${Math.random().toString(36).slice(2)}` });
  }

  async unsentRecords(): Promise<ContributionRecord[]> {
    const result = await this.db.allDocs({ include_docs: true });
    return result.rows
      .map((row) => row.doc as unknown as ContributionRecord)
      .filter((doc) => doc.type === 'contribution' && !this.sentIds.has(doc._id!));
  }

  markSent(ids: string[]): void {
    for (const id of ids) {
      this.sentIds.add(id);
    }
  }

  localDb(): PouchDB.Database {
    return this.db;
  }
}
```

Unlike `device-sim`'s `Device`, this client's local database name is stable (`participant-${participantRef}`, no timestamp/random suffix) -- a real participant reopening the app must reconnect to the SAME IndexedDB database across sessions, not create a fresh one every time, since IndexedDB (unlike the memory adapter phase 5's fleet uses) actually persists across page reloads and that persistence is the whole point.

- [ ] **Step 6: `src/sync.ts`**, `device-sim/src/sync.ts` verbatim except the type name (`Device` to `ParticipantClient`) and dropping the `conflictInjector` re-export (that is a test-harness-only concern specific to `device-sim`'s experiment tooling, not part of a real client):

```typescript
import { createRemoteDb } from './pouchdb';
import { ParticipantClient } from './device';

export async function syncDevice(client: ParticipantClient, remoteUrl: string): Promise<{ pushed: number }> {
  const remote = createRemoteDb(remoteUrl);
  const pending = await client.unsentRecords();
  const result = await new Promise<{ docs_written: number }>((resolve, reject) => {
    client.localDb().replicate.to(remote).on('complete', resolve).on('error', reject);
  });
  client.markSent(pending.map((r) => r._id!));
  return { pushed: result.docs_written };
}

// Identity, not recency, decides -- see device-sim/src/sync.ts's own comment
// (phase 5 spec section 5) for the full reasoning behind this rule; it applies
// unchanged to a real client's own conflicts.
export async function resolveConflicts(client: ParticipantClient, remoteUrl: string): Promise<{ resolvedCount: number }> {
  const remote = createRemoteDb(remoteUrl);
  const allDocs = await remote.allDocs({ include_docs: true, conflicts: true });
  let resolvedCount = 0;

  for (const row of allDocs.rows) {
    const doc = row.doc as unknown as { _id: string; _rev: string; _conflicts?: string[] };
    if (!doc._conflicts || doc._conflicts.length === 0) {
      continue;
    }

    let localDoc: PouchDB.Core.ExistingDocument<Record<string, unknown>>;
    try {
      localDoc = await client.localDb().get(doc._id);
    } catch {
      continue;
    }

    const { _id, _rev, ...content } = localDoc as unknown as { _id: string; _rev: string; [key: string]: unknown };
    await remote.put({ _id: doc._id, _rev: doc._rev, ...content });

    for (const rev of doc._conflicts) {
      await remote.remove(doc._id, rev);
    }
    resolvedCount++;
  }

  return { resolvedCount };
}
```

- [ ] **Step 7: `src/index.ts`** -- this is the fix for `device-sim`'s parked packaging defect, applied from the start:

```typescript
export { ParticipantClient } from './device';
export { syncDevice, resolveConflicts, createRemoteDb } from './sync';
export { createLocalDb } from './pouchdb';
```

(`createRemoteDb` is re-exported from `sync.ts`'s own import surface for convenience; it is defined in `pouchdb.ts` and re-exported once, not twice -- confirm this file only lists it under one of the two export lines when writing it, not both.)

- [ ] **Step 8: `test/setup.ts`**, needed because `pouchdb-adapter-idb` requires a real `indexedDB` global, which Node (where `vitest` runs) does not provide:

```typescript
import 'fake-indexeddb/auto';
```

Add to `package.json`'s test script or a `vitest.config.ts` `setupFiles` entry -- follow whichever pattern the root `vitest` configuration (if any) already uses for other packages; if none exists, add a minimal `vitest.config.ts` in this package:

```typescript
import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    setupFiles: ['./test/setup.ts']
  }
});
```

- [ ] **Step 9: `test/device.test.ts`**, a direct port of `device-sim/test/device.test.ts`'s test cases (read that file first for the exact existing assertions to mirror -- grant/revoke/outbox-sweep behavior is identical logic, only the underlying adapter differs) constructing `ParticipantClient` instead of `Device`.

- [ ] **Step 10: `test/sync.integration.test.ts`**, a direct port of `device-sim/test/sync.integration.test.ts` (read that file's full content, already reproduced in this plan's Global Constraints preamble research) with `Device` replaced by `ParticipantClient`, and `injectConflict` imported from a small local test-only helper instead of `device-sim`'s `conflictInjector` (this package has no dependency on `device-sim`):

```typescript
// test/conflict-injector.ts
export async function injectConflict(remoteUrl: string, docId: string, conflictingBody: unknown): Promise<void> {
  const base = remoteUrl.endsWith('/') ? remoteUrl.slice(0, -1) : remoteUrl;
  const current = await fetch(`${base}/${docId}`).then((r) => r.json()) as { _rev: string };
  const [generation] = current._rev.split('-');
  const bogusRev = `${Number(generation) + 1}-${Math.random().toString(16).slice(2, 34).padEnd(32, '0')}`;
  await fetch(`${base}/${docId}?new_edits=false`, {
    method: 'PUT',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ ...conflictingBody, _id: docId, _rev: bogusRev })
  });
}
```

This mirrors `device-sim/src/conflictInjector.ts`'s approach (direct HTTP PUT with `?new_edits=false` at a higher generation) -- read that file for its exact current behavior before writing this port, since this plan's grounding research did not capture its full literal source.

Include the C3 regression test from `device-sim`'s suite (`'does not retroactively delete an already-synced record when a later revoke sweeps the outbox'`) unchanged in spirit -- it proves the exact same `sentIds`/leak-prevention logic this package's `device.ts` shares verbatim with `device-sim`'s.

- [ ] **Step 11: Run the full test suite**

Run: `cd packages/consent-web-client && npx vitest run`
Expected: unit tests (`device.test.ts`) pass without any external dependency (idb adapter works fully in-process against `fake-indexeddb`). Integration tests (`sync.integration.test.ts`) require live CouchDB, following the same `describe.skipIf`-with-top-level-`await` reachability pattern established in phase 5 (Global Constraints do not repeat this pattern's code here -- copy it from `device-sim/test/sync.integration.test.ts`, already reproduced in full in this plan's preamble research above).

- [ ] **Step 12: Add `consent-web-client` to the workspace and run `npm install`**

`consent-web-client` is under `packages/*`, already covered by the existing `workspaces` glob -- no change needed there (only `apps/*`, Task 12, needs a new glob entry).

Run: `npm install && npm run build --workspace=@benefits/consent-web-client`
Expected: installs cleanly, builds `dist/index.js` and `dist/index.d.ts`.

- [ ] **Step 13: Commit**

```bash
git add packages/consent-web-client
git commit -m "feat(consent-web-client): browser PouchDB participant client"
```

### Task 12: apps/participant-web

**Files:**
- Modify: `package.json` (root -- add `"apps/*"` to `workspaces`)
- Create: `apps/participant-web/package.json`
- Create: `apps/participant-web/tsconfig.json`
- Create: `apps/participant-web/index.html`
- Create: `apps/participant-web/vite.config.ts`
- Create: `apps/participant-web/src/main.tsx`
- Create: `apps/participant-web/src/App.tsx`
- Create: `apps/participant-web/src/ConsentCards.tsx`
- Create: `apps/participant-web/src/PrivacyDashboard.tsx`
- Create: `apps/participant-web/src/AuditViewer.tsx`
- Create: `apps/participant-web/src/config.ts`

**Interfaces:**
- Consumes: Task 11's `@benefits/consent-web-client` (`ParticipantClient`, `syncDevice`, `resolveConflicts`), Task 8's authenticated `AuditResource` (`GET /audit/{participantRef}` with an `Authorization` header).
- Produces: nothing later tasks import from (a leaf app); this is Gate 4's first click-through target.

Real, minimal, demo-grade per the phase 6 gate's own literal text ("manual click through, paper screenshots") -- functional, not polished. Login is a plain participant-ref-plus-CouchDB-credentials form (credentials obtained out of band from the steward-portal provisioning flow, Task 15) stored only in memory for the session, never persisted to `localStorage` (a provisioned participant's password is a real CouchDB credential, not a UI convenience token).

- [ ] **Step 1: Root `package.json`**

```json
{
  "name": "benefits",
  "private": true,
  "workspaces": ["packages/*", "apps/*"],
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

- [ ] **Step 2: Verify React/Vite dependency versions against the real npm registry**, following this plan's established convention (Task 11, Step 1) -- confirm current stable versions of `react`, `react-dom`, `vite`, `@vitejs/plugin-react`, `typescript` types for React, before writing Step 3.

- [ ] **Step 3: `apps/participant-web/package.json`**

```json
{
  "name": "@benefits/participant-web",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc -p tsconfig.json && vite build",
    "test": "echo \"no unit tests in this app; see Gate 4 manual click-through\" && exit 0"
  },
  "dependencies": {
    "@benefits/consent-web-client": "0.1.0",
    "react": "REPLACE_WITH_VERIFIED_VERSION",
    "react-dom": "REPLACE_WITH_VERIFIED_VERSION"
  },
  "devDependencies": {
    "@types/react": "REPLACE_WITH_VERIFIED_VERSION",
    "@types/react-dom": "REPLACE_WITH_VERIFIED_VERSION",
    "@vitejs/plugin-react": "REPLACE_WITH_VERIFIED_VERSION",
    "vite": "REPLACE_WITH_VERIFIED_VERSION",
    "typescript": "5.7.2"
  }
}
```

Replace every `REPLACE_WITH_VERIFIED_VERSION` with the exact version Step 2 confirmed exists -- do not leave any of these placeholders in the committed file.

- [ ] **Step 4: `apps/participant-web/tsconfig.json`**

```json
{
  "compilerOptions": {
    "target": "ES2020",
    "lib": ["ES2020", "DOM"],
    "module": "ESNext",
    "moduleResolution": "bundler",
    "jsx": "react-jsx",
    "strict": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "noEmit": true
  },
  "include": ["src"]
}
```

- [ ] **Step 5: `apps/participant-web/vite.config.ts`**

```typescript
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()]
});
```

- [ ] **Step 6: `apps/participant-web/index.html`**

```html
<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <title>BENEFITS participant</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

- [ ] **Step 7: `apps/participant-web/src/config.ts`**

```typescript
export const COUCHDB_BASE_URL = import.meta.env.VITE_COUCHDB_URL ?? 'http://localhost:5984';
export const AUDIT_BASE_URL = import.meta.env.VITE_AUDIT_URL ?? 'http://localhost:19197/audit';
```

- [ ] **Step 8: `apps/participant-web/src/main.tsx`**

```typescript
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>
);
```

- [ ] **Step 9: `apps/participant-web/src/App.tsx`**

```typescript
import { useState } from 'react';
import { ConsentCards } from './ConsentCards';
import { PrivacyDashboard } from './PrivacyDashboard';
import { AuditViewer } from './AuditViewer';

type Session = { participantRef: string; password: string };
type Tab = 'consent' | 'dashboard' | 'audit';

export function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [tab, setTab] = useState<Tab>('consent');

  if (!session) {
    return <LoginForm onLogin={setSession} />;
  }

  return (
    <div>
      <nav>
        <button onClick={() => setTab('consent')}>Consent</button>
        <button onClick={() => setTab('dashboard')}>Privacy dashboard</button>
        <button onClick={() => setTab('audit')}>Audit trail</button>
      </nav>
      {tab === 'consent' && <ConsentCards session={session} />}
      {tab === 'dashboard' && <PrivacyDashboard session={session} />}
      {tab === 'audit' && <AuditViewer session={session} />}
    </div>
  );
}

function LoginForm({ onLogin }: { onLogin: (session: Session) => void }) {
  const [participantRef, setParticipantRef] = useState('');
  const [password, setPassword] = useState('');

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        onLogin({ participantRef, password });
      }}
    >
      <label>
        Participant ID
        <input value={participantRef} onChange={(e) => setParticipantRef(e.target.value)} required />
      </label>
      <label>
        Password
        <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
      </label>
      <button type="submit">Continue</button>
    </form>
  );
}

export type { Session };
```

- [ ] **Step 10: `apps/participant-web/src/ConsentCards.tsx`**

```typescript
import { useEffect, useState } from 'react';
import { ParticipantClient, syncDevice, resolveConflicts } from '@benefits/consent-web-client';
import type { ConsentGrant } from '@benefits/consent-core';
import { COUCHDB_BASE_URL } from './config';
import type { Session } from './App';

export function ConsentCards({ session }: { session: Session }) {
  const [client] = useState(() => new ParticipantClient(session.participantRef));
  const [grants, setGrants] = useState<ConsentGrant[]>([]);
  const [status, setStatus] = useState('');

  useEffect(() => {
    setGrants(client.grants());
  }, [client]);

  const remoteUrl = COUCHDB_BASE_URL.replace(
    '://', `://${encodeURIComponent(session.participantRef)}:${encodeURIComponent(session.password)}@`
  ) + '/benefits';

  async function grant(field: string, purpose: string, recipientClass: 'community' | 'provider' | 'statistics') {
    await client.grantConsent({
      field, purpose, recipientClass,
      grantedAt: new Date().toISOString(), expiresAt: null, revokedAt: null
    });
    setGrants(client.grants());
  }

  async function revoke(field: string, purpose: string, recipientClass: string) {
    const { sweptCount } = await client.revoke(field, purpose, recipientClass, new Date().toISOString());
    setGrants(client.grants());
    setStatus(`revoked (swept ${sweptCount} queued record${sweptCount === 1 ? '' : 's'} from the outbox)`);
  }

  async function sync() {
    setStatus('syncing...');
    const { pushed } = await syncDevice(client, remoteUrl);
    const { resolvedCount } = await resolveConflicts(client, remoteUrl);
    setStatus(`pushed ${pushed} record${pushed === 1 ? '' : 's'}, resolved ${resolvedCount} conflict${resolvedCount === 1 ? '' : 's'}`);
  }

  return (
    <div>
      <h2>Consent</h2>
      <button onClick={() => grant('service.satisfaction', 'service-improvement', 'statistics')}>
        Grant: satisfaction data for service improvement (statistics)
      </button>
      <ul>
        {grants.map((g, i) => (
          <li key={i}>
            {g.field} / {g.purpose} / {g.recipientClass}
            {g.revokedAt ? ' (revoked)' : (
              <button onClick={() => revoke(g.field, g.purpose, g.recipientClass)}>Revoke</button>
            )}
          </li>
        ))}
      </ul>
      <button onClick={sync}>Sync now</button>
      <p>{status}</p>
    </div>
  );
}
```

- [ ] **Step 11: `apps/participant-web/src/PrivacyDashboard.tsx`**

```typescript
import { useEffect, useState } from 'react';
import { ParticipantClient } from '@benefits/consent-web-client';
import type { ConsentGrant } from '@benefits/consent-core';
import type { Session } from './App';

export function PrivacyDashboard({ session }: { session: Session }) {
  const [grants, setGrants] = useState<ConsentGrant[]>([]);

  useEffect(() => {
    const client = new ParticipantClient(session.participantRef);
    setGrants(client.grants());
  }, [session.participantRef]);

  const active = grants.filter((g) => !g.revokedAt && (!g.expiresAt || g.expiresAt > new Date().toISOString()));

  return (
    <div>
      <h2>Your active grants</h2>
      <p>{active.length} active grant{active.length === 1 ? '' : 's'} out of {grants.length} total.</p>
      <ul>
        {active.map((g, i) => (
          <li key={i}>{g.field} shared for {g.purpose} with {g.recipientClass}, since {g.grantedAt}</li>
        ))}
      </ul>
    </div>
  );
}
```

- [ ] **Step 12: `apps/participant-web/src/AuditViewer.tsx`**

```typescript
import { useState } from 'react';
import { AUDIT_BASE_URL } from './config';
import type { Session } from './App';

type AuditEntry = { seq: number; timestamp: string; eventType: string; datasetRef: string; plainLanguage: string };
type AuditResponse = { entries: AuditEntry[] };

export function AuditViewer({ session }: { session: Session }) {
  const [entries, setEntries] = useState<AuditEntry[] | null>(null);
  const [error, setError] = useState('');

  async function load() {
    setError('');
    const auth = 'Basic ' + btoa(`${session.participantRef}:${session.password}`);
    const response = await fetch(`${AUDIT_BASE_URL}/${encodeURIComponent(session.participantRef)}`, {
      headers: { authorization: auth }
    });
    if (response.status === 403) {
      setError('Not authorized to view this audit trail.');
      return;
    }
    const body = await response.json() as AuditResponse;
    setEntries(body.entries);
  }

  return (
    <div>
      <h2>Audit trail</h2>
      <button onClick={load}>Load</button>
      {error && <p>{error}</p>}
      {entries && (
        <ul>
          {entries.map((e) => (
            <li key={e.seq}>{e.timestamp}: {e.plainLanguage}</li>
          ))}
        </ul>
      )}
    </div>
  );
}
```

- [ ] **Step 13: Install and build**

Run: `npm install && npm run build --workspace=@benefits/participant-web`
Expected: builds without type errors.

- [ ] **Step 14: Manual click-through** (Gate 4 for this app -- start the dev server and exercise the golden path in a real browser)

Run: `npm run dev --workspace=@benefits/participant-web` (requires CouchDB running and at least one participant already provisioned via Task 9's `POST /steward/participants/{participantRef}/provision` or Task 10's script, for a genuine login). Open the printed local URL, log in, grant a consent, sync, revoke, sync again, and load the audit trail. Confirm no console errors and that each action's effect is visible (grant appears in the list, sync reports a pushed count, revoke shows a swept count, audit entries load once the account has at least one recorded event).

- [ ] **Step 15: Commit**

```bash
git add package.json apps/participant-web
git commit -m "feat(participant-web): consent cards, privacy dashboard, audit viewer"
```

### Task 13: apps/participant-mobile core (Flutter outbox, client, sync)

**Files:**
- Create: `apps/participant-mobile/pubspec.yaml`
- Create: `apps/participant-mobile/lib/models.dart`
- Create: `apps/participant-mobile/lib/outbox.dart`
- Create: `apps/participant-mobile/lib/participant_client.dart`
- Create: `apps/participant-mobile/lib/sync.dart`
- Create: `apps/participant-mobile/test/outbox_test.dart`
- Create: `apps/participant-mobile/test/participant_client_test.dart`

**Interfaces:**
- Consumes: nothing from earlier tasks (Dart has no way to import TypeScript packages; this is an independent port of the same leak-prevention logic, not a code dependency).
- Produces: `Outbox`, `ParticipantClient`, `syncOutbox`. Task 14 (Flutter UI) builds screens on top of these.

Per the parent spec's already-recorded Flutter decision: a local outbox, plain HTTP pushes to CouchDB (no PouchDB replication library exists for Dart against real CouchDB), no bidirectional conflict resolution. `sqflite` is the local outbox backend; `sqflite_common_ffi` lets these tests run as plain Dart unit tests (no emulator/device needed) -- `sqflite`'s default platform channel implementation does not work under `flutter test` without it, a common, well-documented Flutter testing gotcha.

- [ ] **Step 1: Verify Dart package versions against pub.dev before writing `pubspec.yaml`** -- confirm current stable versions of `sqflite`, `path`, `http`, `sqflite_common_ffi`, and the Flutter/Dart SDK constraint this repo's other tooling implies (no Flutter code exists yet in this repo to match against; use whatever stable Flutter SDK is available in the implementation environment, and record the exact version used in the task report).

- [ ] **Step 2: `pubspec.yaml`**

```yaml
name: benefits_participant_mobile
description: BENEFITS participant mobile client (phase 6)
publish_to: 'none'
version: 0.1.0

environment:
  sdk: '>=3.4.0 <4.0.0'

dependencies:
  flutter:
    sdk: flutter
  sqflite: REPLACE_WITH_VERIFIED_VERSION
  path: REPLACE_WITH_VERIFIED_VERSION
  http: REPLACE_WITH_VERIFIED_VERSION

dev_dependencies:
  flutter_test:
    sdk: flutter
  sqflite_common_ffi: REPLACE_WITH_VERIFIED_VERSION

flutter:
  uses-material-design: true
```

Replace every `REPLACE_WITH_VERIFIED_VERSION` with the real version Step 1 confirmed.

- [ ] **Step 3: Write the failing `outbox_test.dart`**

```dart
import 'package:flutter_test/flutter_test.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:benefits_participant_mobile/outbox.dart';

void main() {
  sqfliteFfiInit();

  test('enqueue then unsent returns the record', () async {
    final db = await databaseFactoryFfi.openDatabase(inMemoryDatabasePath);
    final outbox = await Outbox.open(db);
    await outbox.enqueue('c-1', {'type': 'contribution', 'fields': {'service.satisfaction': 4}});
    final unsent = await outbox.unsent();
    expect(unsent.length, 1);
    expect(unsent.first.key, 'c-1');
  });

  test('markSent excludes a record from unsent', () async {
    final db = await databaseFactoryFfi.openDatabase(inMemoryDatabasePath);
    final outbox = await Outbox.open(db);
    await outbox.enqueue('c-2', {'type': 'contribution'});
    await outbox.markSent(['c-2']);
    final unsent = await outbox.unsent();
    expect(unsent, isEmpty);
  });

  test('sweepUnsentMatching deletes only matching unsent records', () async {
    final db = await databaseFactoryFfi.openDatabase(inMemoryDatabasePath);
    final outbox = await Outbox.open(db);
    await outbox.enqueue('c-3', {
      'fields': {'service.satisfaction': 4},
      'declaredPurpose': 'service-improvement',
      'declaredRecipientClass': 'statistics'
    });
    await outbox.enqueue('c-4', {
      'fields': {'other.field': 1},
      'declaredPurpose': 'marketing',
      'declaredRecipientClass': 'provider'
    });
    final swept = await outbox.sweepUnsentMatching((body) =>
        (body['fields'] as Map).containsKey('service.satisfaction') &&
        body['declaredPurpose'] == 'service-improvement');
    expect(swept, 1);
    final remaining = await outbox.unsent();
    expect(remaining.map((e) => e.key), contains('c-4'));
    expect(remaining.map((e) => e.key), isNot(contains('c-3')));
  });

  test('does not sweep an already-sent record on a later revoke', () async {
    final db = await databaseFactoryFfi.openDatabase(inMemoryDatabasePath);
    final outbox = await Outbox.open(db);
    await outbox.enqueue('c-5', {
      'fields': {'service.satisfaction': 4},
      'declaredPurpose': 'service-improvement',
      'declaredRecipientClass': 'statistics'
    });
    await outbox.markSent(['c-5']);
    final swept = await outbox.sweepUnsentMatching((body) =>
        (body['fields'] as Map).containsKey('service.satisfaction') &&
        body['declaredPurpose'] == 'service-improvement');
    expect(swept, 0);
  });
}
```

This last test is the same C3 regression phase 5's `device-sim` and Task 11's `consent-web-client` both carry -- here it holds structurally, since `sweepUnsentMatching` only ever queries rows where `sent = 0`, so an already-sent record can never be matched regardless of its content.

- [ ] **Step 4: Run to verify it fails**

Run: `cd apps/participant-mobile && flutter test test/outbox_test.dart`
Expected: FAIL (`Outbox` does not exist).

- [ ] **Step 5: Implement `Outbox`**

```dart
import 'dart:convert';
import 'package:sqflite/sqflite.dart';

class Outbox {
  final Database db;

  Outbox._(this.db);

  static Future<Outbox> open(Database db) async {
    await db.execute('''
      CREATE TABLE IF NOT EXISTS outbox (
        id TEXT PRIMARY KEY,
        body TEXT NOT NULL,
        sent INTEGER NOT NULL DEFAULT 0
      )
    ''');
    return Outbox._(db);
  }

  Future<void> enqueue(String id, Map<String, dynamic> body) async {
    await db.insert(
      'outbox',
      {'id': id, 'body': jsonEncode(body), 'sent': 0},
      conflictAlgorithm: ConflictAlgorithm.replace,
    );
  }

  Future<List<MapEntry<String, Map<String, dynamic>>>> unsent() async {
    final rows = await db.query('outbox', where: 'sent = 0');
    return rows
        .map((row) => MapEntry(row['id'] as String, jsonDecode(row['body'] as String) as Map<String, dynamic>))
        .toList();
  }

  Future<void> markSent(List<String> ids) async {
    if (ids.isEmpty) {
      return;
    }
    final placeholders = List.filled(ids.length, '?').join(',');
    await db.update('outbox', {'sent': 1}, where: 'id IN ($placeholders)', whereArgs: ids);
  }

  Future<int> sweepUnsentMatching(bool Function(Map<String, dynamic> body) predicate) async {
    final rows = await db.query('outbox', where: 'sent = 0');
    var swept = 0;
    for (final row in rows) {
      final body = jsonDecode(row['body'] as String) as Map<String, dynamic>;
      if (predicate(body)) {
        await db.delete('outbox', where: 'id = ?', whereArgs: [row['id']]);
        swept++;
      }
    }
    return swept;
  }
}
```

- [ ] **Step 6: Run to verify it passes**

Run: `cd apps/participant-mobile && flutter test test/outbox_test.dart`
Expected: PASS.

- [ ] **Step 7: Write `models.dart`**

```dart
class ConsentGrant {
  final String field;
  final String purpose;
  final String recipientClass;
  final String grantedAt;
  String? expiresAt;
  String? revokedAt;

  ConsentGrant({
    required this.field,
    required this.purpose,
    required this.recipientClass,
    required this.grantedAt,
    this.expiresAt,
    this.revokedAt,
  });

  Map<String, dynamic> toJson() => {
        'field': field,
        'purpose': purpose,
        'recipientClass': recipientClass,
        'grantedAt': grantedAt,
        'expiresAt': expiresAt,
        'revokedAt': revokedAt,
      };
}
```

- [ ] **Step 8: Write the failing `participant_client_test.dart`**

```dart
import 'package:flutter_test/flutter_test.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:benefits_participant_mobile/models.dart';
import 'package:benefits_participant_mobile/outbox.dart';
import 'package:benefits_participant_mobile/participant_client.dart';

void main() {
  sqfliteFfiInit();

  Future<ParticipantClient> newClient() async {
    final db = await databaseFactoryFfi.openDatabase(inMemoryDatabasePath);
    final outbox = await Outbox.open(db);
    return ParticipantClient('p-1', outbox);
  }

  test('grantConsent then grants returns the grant', () async {
    final client = await newClient();
    client.grantConsent(ConsentGrant(
      field: 'service.satisfaction', purpose: 'service-improvement', recipientClass: 'statistics',
      grantedAt: '2026-09-01T00:00:00Z',
    ));
    expect(client.grants().length, 1);
  });

  test('revoke stamps revokedAt and sweeps only the matching unsent record', () async {
    final client = await newClient();
    client.grantConsent(ConsentGrant(
      field: 'service.satisfaction', purpose: 'service-improvement', recipientClass: 'statistics',
      grantedAt: '2026-09-01T00:00:00Z',
    ));
    await client.contribute('c-1', {
      'type': 'contribution',
      'fields': {'service.satisfaction': 4},
      'declaredPurpose': 'service-improvement',
      'declaredRecipientClass': 'statistics',
    });

    final swept = await client.revoke('service.satisfaction', 'service-improvement', 'statistics', '2026-09-01T01:00:00Z');

    expect(swept, 1);
    expect(client.grants().first.revokedAt, '2026-09-01T01:00:00Z');
  });
}
```

- [ ] **Step 9: Run to verify it fails, then implement `participant_client.dart`**

```dart
import 'models.dart';
import 'outbox.dart';

class ParticipantClient {
  final String participantRef;
  final Outbox outbox;
  final List<ConsentGrant> _grants = [];

  ParticipantClient(this.participantRef, this.outbox);

  void grantConsent(ConsentGrant grant) {
    _grants.add(grant);
  }

  List<ConsentGrant> grants() => List.unmodifiable(_grants);

  Future<void> contribute(String id, Map<String, dynamic> record) async {
    await outbox.enqueue(id, record);
  }

  Future<int> revoke(String field, String purpose, String recipientClass, String atIso) async {
    for (final grant in _grants) {
      if (grant.field == field && grant.purpose == purpose && grant.recipientClass == recipientClass) {
        grant.revokedAt = atIso;
      }
    }
    return outbox.sweepUnsentMatching((body) {
      final fields = (body['fields'] as Map<String, dynamic>?) ?? {};
      return fields.containsKey(field) &&
          body['declaredPurpose'] == purpose &&
          body['declaredRecipientClass'] == recipientClass;
    });
  }
}
```

Run: `cd apps/participant-mobile && flutter test test/participant_client_test.dart`
Expected: PASS.

- [ ] **Step 10: Write `sync.dart`** (no test in this step -- an HTTP-calling function against real CouchDB is exercised by Gate 0's probe and Gate 4's manual click-through, Task 14, not a unit test; its logic is a thin loop with no branching worth a mock-HTTP unit test on its own)

```dart
import 'dart:convert';
import 'package:http/http.dart' as http;
import 'outbox.dart';

class SyncResult {
  final int pushed;
  SyncResult(this.pushed);
}

/// Pushes each unsent outbox record directly to CouchDB via a per-document PUT --
/// no PouchDB replication (the already-recorded Flutter decision, parent spec
/// section 1.2). A record is marked sent only after its own individual PUT
/// succeeds, one at a time, not as a whole-batch operation on a single
/// "replication complete" event the way device-sim's original TypeScript sync
/// does -- this avoids that implementation's own parked gap (a single failed
/// document among several would have marked the whole batch sent regardless).
Future<SyncResult> syncOutbox(Outbox outbox, String remoteUrl, String authHeader) async {
  final pending = await outbox.unsent();
  final pushedIds = <String>[];
  for (final entry in pending) {
    final response = await http.put(
      Uri.parse('$remoteUrl/${entry.key}'),
      headers: {'content-type': 'application/json', 'authorization': authHeader},
      body: jsonEncode(entry.value),
    );
    if (response.statusCode == 201 || response.statusCode == 200) {
      pushedIds.add(entry.key);
    }
  }
  await outbox.markSent(pushedIds);
  return SyncResult(pushedIds.length);
}
```

- [ ] **Step 11: Run the full test suite**

Run: `cd apps/participant-mobile && flutter test`
Expected: all `outbox_test.dart` and `participant_client_test.dart` tests PASS.

- [ ] **Step 12: Commit**

```bash
git add apps/participant-mobile/pubspec.yaml apps/participant-mobile/lib apps/participant-mobile/test
git commit -m "feat(participant-mobile): local outbox, participant client, CouchDB sync"
```

### Task 14: apps/participant-mobile UI (Flutter screens)

**Files:**
- Modify: `apps/participant-mobile/pubspec.yaml` (add `sqflite` platform init is already present; no new dependency needed for plain Material widgets)
- Create: `apps/participant-mobile/lib/main.dart`
- Create: `apps/participant-mobile/lib/login_screen.dart`
- Create: `apps/participant-mobile/lib/consent_screen.dart`
- Create: `apps/participant-mobile/lib/dashboard_screen.dart`
- Create: `apps/participant-mobile/lib/audit_screen.dart`
- Create: `apps/participant-mobile/lib/session.dart`

**Interfaces:**
- Consumes: Task 13's `Outbox`, `ParticipantClient`, `syncOutbox`.
- Produces: a runnable Flutter app; Gate 4's manual click-through target for the mobile surface.

Plain Material widgets, no design system -- matches the phase 6 gate's own "manual click through, paper screenshots" text. Session state (participant ref + CouchDB password, obtained out of band from steward-portal provisioning) lives in memory only, passed down via constructor parameters, not a state-management library -- this app has four screens and does not need one.

- [ ] **Step 1: `lib/session.dart`**

```dart
class Session {
  final String participantRef;
  final String password;
  final String couchDbBaseUrl;
  final String auditBaseUrl;

  Session({
    required this.participantRef,
    required this.password,
    this.couchDbBaseUrl = 'http://localhost:5984',
    this.auditBaseUrl = 'http://localhost:19197/audit',
  });

  String get authHeader {
    final token = '$participantRef:$password';
    return 'Basic ${base64Encode(utf8.encode(token))}';
  }

  String get remoteDatabaseUrl => '$couchDbBaseUrl/benefits';
}

import 'dart:convert';
```

(The `import 'dart:convert';` line above is placed after the class body only to keep this plan's example compact -- write it as the file's first line, before the class declaration, as Dart requires.)

- [ ] **Step 2: `lib/login_screen.dart`**

```dart
import 'package:flutter/material.dart';
import 'session.dart';

class LoginScreen extends StatefulWidget {
  final void Function(Session session) onLogin;

  const LoginScreen({super.key, required this.onLogin});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final _participantRefController = TextEditingController();
  final _passwordController = TextEditingController();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('BENEFITS participant')),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          children: [
            TextField(controller: _participantRefController, decoration: const InputDecoration(labelText: 'Participant ID')),
            TextField(controller: _passwordController, decoration: const InputDecoration(labelText: 'Password'), obscureText: true),
            const SizedBox(height: 16),
            ElevatedButton(
              onPressed: () {
                widget.onLogin(Session(
                  participantRef: _participantRefController.text,
                  password: _passwordController.text,
                ));
              },
              child: const Text('Continue'),
            ),
          ],
        ),
      ),
    );
  }
}
```

- [ ] **Step 3: `lib/consent_screen.dart`**

```dart
import 'package:flutter/material.dart';
import 'package:sqflite/sqflite.dart';
import 'models.dart';
import 'outbox.dart';
import 'participant_client.dart';
import 'session.dart';
import 'sync.dart';

class ConsentScreen extends StatefulWidget {
  final Session session;

  const ConsentScreen({super.key, required this.session});

  @override
  State<ConsentScreen> createState() => _ConsentScreenState();
}

class _ConsentScreenState extends State<ConsentScreen> {
  ParticipantClient? _client;
  String _status = '';

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    final db = await openDatabase('participant-${widget.session.participantRef}.db');
    final outbox = await Outbox.open(db);
    setState(() {
      _client = ParticipantClient(widget.session.participantRef, outbox);
    });
  }

  Future<void> _grant() async {
    _client!.grantConsent(ConsentGrant(
      field: 'service.satisfaction', purpose: 'service-improvement', recipientClass: 'statistics',
      grantedAt: DateTime.now().toUtc().toIso8601String(),
    ));
    setState(() {});
  }

  Future<void> _revoke(ConsentGrant grant) async {
    final swept = await _client!.revoke(grant.field, grant.purpose, grant.recipientClass, DateTime.now().toUtc().toIso8601String());
    setState(() {
      _status = 'revoked (swept $swept queued record${swept == 1 ? '' : 's'})';
    });
  }

  Future<void> _sync() async {
    setState(() => _status = 'syncing...');
    final result = await syncOutbox(_client!.outbox, widget.session.remoteDatabaseUrl, widget.session.authHeader);
    setState(() => _status = 'pushed ${result.pushed} record${result.pushed == 1 ? '' : 's'}');
  }

  @override
  Widget build(BuildContext context) {
    if (_client == null) {
      return const Center(child: CircularProgressIndicator());
    }
    return Column(
      children: [
        ElevatedButton(onPressed: _grant, child: const Text('Grant: satisfaction data for service improvement')),
        Expanded(
          child: ListView(
            children: _client!.grants().map((g) {
              return ListTile(
                title: Text('${g.field} / ${g.purpose} / ${g.recipientClass}'),
                subtitle: g.revokedAt != null ? const Text('revoked') : null,
                trailing: g.revokedAt == null
                    ? TextButton(onPressed: () => _revoke(g), child: const Text('Revoke'))
                    : null,
              );
            }).toList(),
          ),
        ),
        ElevatedButton(onPressed: _sync, child: const Text('Sync now')),
        Text(_status),
      ],
    );
  }
}
```

- [ ] **Step 4: `lib/dashboard_screen.dart`**

```dart
import 'package:flutter/material.dart';
import 'package:sqflite/sqflite.dart';
import 'outbox.dart';
import 'participant_client.dart';
import 'session.dart';

class DashboardScreen extends StatefulWidget {
  final Session session;

  const DashboardScreen({super.key, required this.session});

  @override
  State<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends State<DashboardScreen> {
  ParticipantClient? _client;

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    final db = await openDatabase('participant-${widget.session.participantRef}.db');
    final outbox = await Outbox.open(db);
    setState(() {
      _client = ParticipantClient(widget.session.participantRef, outbox);
    });
  }

  @override
  Widget build(BuildContext context) {
    if (_client == null) {
      return const Center(child: CircularProgressIndicator());
    }
    final active = _client!.grants().where((g) => g.revokedAt == null).toList();
    return ListView(
      children: [
        Padding(
          padding: const EdgeInsets.all(16),
          child: Text('${active.length} active grant${active.length == 1 ? '' : 's'} of ${_client!.grants().length} total'),
        ),
        ...active.map((g) => ListTile(
              title: Text(g.field),
              subtitle: Text('shared for ${g.purpose} with ${g.recipientClass}, since ${g.grantedAt}'),
            )),
      ],
    );
  }
}
```

- [ ] **Step 5: `lib/audit_screen.dart`**

```dart
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'session.dart';

class AuditScreen extends StatefulWidget {
  final Session session;

  const AuditScreen({super.key, required this.session});

  @override
  State<AuditScreen> createState() => _AuditScreenState();
}

class _AuditScreenState extends State<AuditScreen> {
  List<dynamic>? _entries;
  String _error = '';

  Future<void> _load() async {
    setState(() => _error = '');
    final uri = Uri.parse('${widget.session.auditBaseUrl}/${Uri.encodeComponent(widget.session.participantRef)}');
    final response = await http.get(uri, headers: {'authorization': widget.session.authHeader});
    if (response.statusCode == 403) {
      setState(() => _error = 'Not authorized to view this audit trail.');
      return;
    }
    final body = jsonDecode(response.body) as Map<String, dynamic>;
    setState(() => _entries = body['entries'] as List<dynamic>);
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        ElevatedButton(onPressed: _load, child: const Text('Load')),
        if (_error.isNotEmpty) Text(_error),
        if (_entries != null)
          Expanded(
            child: ListView(
              children: _entries!.map((e) {
                final entry = e as Map<String, dynamic>;
                return ListTile(title: Text('${entry['timestamp']}: ${entry['plainLanguage']}'));
              }).toList(),
            ),
          ),
      ],
    );
  }
}
```

- [ ] **Step 6: `lib/main.dart`**

```dart
import 'package:flutter/material.dart';
import 'audit_screen.dart';
import 'consent_screen.dart';
import 'dashboard_screen.dart';
import 'login_screen.dart';
import 'session.dart';

void main() {
  runApp(const BenefitsApp());
}

class BenefitsApp extends StatefulWidget {
  const BenefitsApp({super.key});

  @override
  State<BenefitsApp> createState() => _BenefitsAppState();
}

class _BenefitsAppState extends State<BenefitsApp> {
  Session? _session;
  int _tab = 0;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'BENEFITS participant',
      home: _session == null
          ? LoginScreen(onLogin: (session) => setState(() => _session = session))
          : Scaffold(
              appBar: AppBar(title: const Text('BENEFITS participant')),
              body: [
                ConsentScreen(session: _session!),
                DashboardScreen(session: _session!),
                AuditScreen(session: _session!),
              ][_tab],
              bottomNavigationBar: BottomNavigationBar(
                currentIndex: _tab,
                onTap: (i) => setState(() => _tab = i),
                items: const [
                  BottomNavigationBarItem(icon: Icon(Icons.check_circle), label: 'Consent'),
                  BottomNavigationBarItem(icon: Icon(Icons.dashboard), label: 'Dashboard'),
                  BottomNavigationBarItem(icon: Icon(Icons.history), label: 'Audit'),
                ],
              ),
            ),
    );
  }
}
```

- [ ] **Step 7: Run the existing test suite to confirm no regression**

Run: `cd apps/participant-mobile && flutter test`
Expected: Task 13's tests still PASS (this task adds no new automated tests -- UI screens are Gate 4's manual click-through target, matching the phase 6 gate's own "manual click through, paper screenshots" text, the same standard `apps/participant-web` (Task 12) was held to).

- [ ] **Step 8: Manual click-through** (Gate 4 for this app)

Run: `cd apps/participant-mobile && flutter run` on a connected device or emulator (requires CouchDB reachable from that device/emulator -- on an Android emulator, `localhost` inside the emulator is not the host machine; use `10.0.2.2` or the host's real LAN address in `Session`'s default URLs for this manual run). Log in with a provisioned participant's credentials (Task 9 or Task 10), grant a consent, sync, revoke, sync again, load the audit trail. Confirm each screen updates and no exception is thrown.

- [ ] **Step 9: Commit**

```bash
git add apps/participant-mobile/lib
git commit -m "feat(participant-mobile): consent, dashboard, and audit screens"
```

### Task 15: apps/steward-portal

**Files:**
- Create: `apps/steward-portal/package.json`
- Create: `apps/steward-portal/tsconfig.json`
- Create: `apps/steward-portal/index.html`
- Create: `apps/steward-portal/vite.config.ts`
- Create: `apps/steward-portal/src/main.tsx`
- Create: `apps/steward-portal/src/App.tsx`
- Create: `apps/steward-portal/src/config.ts`
- Create: `apps/steward-portal/src/Queue.tsx`
- Create: `apps/steward-portal/src/ProvisionParticipant.tsx`

**Interfaces:**
- Consumes: Task 9's `StewardResource` (`GET /steward/queue`, `POST /steward/{id}/review`, `POST /steward/{id}/co-sign`, `POST /steward/{id}/veto`, `POST /steward/{id}/appeal`, `POST /steward/participants/{participantRef}/provision`).
- Produces: nothing later tasks import from (a leaf app); Gate 4's second click-through target.

Same conventions as Task 12 (`apps/participant-web`): plain, functional, in-memory-only session credentials, no CSS framework. A steward logs in with the CouchDB credentials Task 10's bootstrap script (or a prior provisioning round) issued.

- [ ] **Step 1: `apps/steward-portal/package.json`** (same dependency set and verified-version discipline as Task 12, Step 2 -- reuse the exact versions confirmed there rather than re-verifying, since both apps share the same React/Vite stack)

```json
{
  "name": "@benefits/steward-portal",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc -p tsconfig.json && vite build",
    "test": "echo \"no unit tests in this app; see Gate 4 manual click-through\" && exit 0"
  },
  "dependencies": {
    "react": "SAME_VERSION_AS_TASK_12",
    "react-dom": "SAME_VERSION_AS_TASK_12"
  },
  "devDependencies": {
    "@types/react": "SAME_VERSION_AS_TASK_12",
    "@types/react-dom": "SAME_VERSION_AS_TASK_12",
    "@vitejs/plugin-react": "SAME_VERSION_AS_TASK_12",
    "vite": "SAME_VERSION_AS_TASK_12",
    "typescript": "5.7.2"
  }
}
```

Replace every `SAME_VERSION_AS_TASK_12` with the literal version string Task 12 used (read `apps/participant-web/package.json`, already committed, to copy them exactly).

- [ ] **Step 2: `apps/steward-portal/tsconfig.json`, `vite.config.ts`, `index.html`** -- identical in shape to Task 12's Steps 4-6, with the page title changed to "BENEFITS steward portal" and no reference to `@benefits/consent-web-client` (this app talks only to `StewardResource`, never to CouchDB directly).

- [ ] **Step 3: `apps/steward-portal/src/config.ts`**

```typescript
export const STEWARD_API_BASE_URL = import.meta.env.VITE_STEWARD_API_URL ?? 'http://localhost:19199/steward';
```

- [ ] **Step 4: `apps/steward-portal/src/main.tsx`** -- identical in shape to Task 12's Step 8, rendering `App` from this app's own `App.tsx`.

- [ ] **Step 5: `apps/steward-portal/src/App.tsx`**

```typescript
import { useState } from 'react';
import { Queue } from './Queue';
import { ProvisionParticipant } from './ProvisionParticipant';

export type Session = { stewardId: string; password: string };
type Tab = 'queue' | 'provision';

export function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [tab, setTab] = useState<Tab>('queue');

  if (!session) {
    return <LoginForm onLogin={setSession} />;
  }

  return (
    <div>
      <nav>
        <button onClick={() => setTab('queue')}>Review queue</button>
        <button onClick={() => setTab('provision')}>Provision participant</button>
      </nav>
      {tab === 'queue' && <Queue session={session} />}
      {tab === 'provision' && <ProvisionParticipant session={session} />}
    </div>
  );
}

function LoginForm({ onLogin }: { onLogin: (session: Session) => void }) {
  const [stewardId, setStewardId] = useState('');
  const [password, setPassword] = useState('');

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        onLogin({ stewardId, password });
      }}
    >
      <label>
        Steward ID
        <input value={stewardId} onChange={(e) => setStewardId(e.target.value)} required />
      </label>
      <label>
        Password
        <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
      </label>
      <button type="submit">Continue</button>
    </form>
  );
}
```

- [ ] **Step 6: `apps/steward-portal/src/Queue.tsx`**

```typescript
import { useEffect, useState } from 'react';
import { STEWARD_API_BASE_URL } from './config';
import type { Session } from './App';

type StewardSignature = { stewardId: string; signature: string; signedAt: string };
type StewardVeto = { stewardId: string; reason: string; vetoedAt: string };
type StewardAppeal = { appellantId: string; reason: string; vetoStewardId: string; appealedAt: string };
type StewardAuthorization = {
  releaseRequestId: string; datasetHash: string;
  signatures: StewardSignature[]; vetoes: StewardVeto[]; appeals: StewardAppeal[];
} | null;
type ReleaseRequest = { releaseRequestId: string; purpose: string; recipientClass: string; fields: string[]; siteId: string };
type QueueEntry = { request: ReleaseRequest; authorization: StewardAuthorization };

function authHeader(session: Session): string {
  return 'Basic ' + btoa(`${session.stewardId}:${session.password}`);
}

export function Queue({ session }: { session: Session }) {
  const [entries, setEntries] = useState<QueueEntry[]>([]);
  const [status, setStatus] = useState('');

  async function load() {
    const response = await fetch(`${STEWARD_API_BASE_URL}/queue`, { headers: { authorization: authHeader(session) } });
    if (response.status === 403) {
      setStatus('Not authorized (is this a steward account?)');
      return;
    }
    setEntries(await response.json() as QueueEntry[]);
  }

  useEffect(() => { load(); }, []);

  async function act(requestId: string, action: 'co-sign' | 'veto', body: Record<string, unknown>) {
    await fetch(`${STEWARD_API_BASE_URL}/${encodeURIComponent(requestId)}/${action}`, {
      method: 'POST',
      headers: { authorization: authHeader(session), 'content-type': 'application/json' },
      body: JSON.stringify(body)
    });
    await load();
  }

  return (
    <div>
      <h2>Review queue</h2>
      <p>{status}</p>
      <ul>
        {entries.map((entry) => (
          <li key={entry.request.releaseRequestId}>
            <strong>{entry.request.releaseRequestId}</strong>: {entry.request.purpose} / {entry.request.recipientClass}
            {' '}(fields: {entry.request.fields.join(', ')}, site: {entry.request.siteId})
            <div>
              signatures: {entry.authorization?.signatures.length ?? 0}, vetoes: {entry.authorization?.vetoes.length ?? 0}
            </div>
            <button onClick={() => act(entry.request.releaseRequestId, 'co-sign', { datasetHash: 'hash-' + entry.request.releaseRequestId, signature: 'approved' })}>
              Co-sign
            </button>
            <button onClick={() => act(entry.request.releaseRequestId, 'veto', { datasetHash: 'hash-' + entry.request.releaseRequestId, reason: 'needs revision' })}>
              Veto
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}
```

The `datasetHash` values above (`'hash-' + releaseRequestId`) are placeholder demo data, matching the spec ruling in Task 9 that no code anywhere in this repo computes a real dataset hash -- a click-through demo needs some deterministic value to pass, not a real hashing scheme.

- [ ] **Step 7: `apps/steward-portal/src/ProvisionParticipant.tsx`**

```typescript
import { useState } from 'react';
import { STEWARD_API_BASE_URL } from './config';
import type { Session } from './App';

export function ProvisionParticipant({ session }: { session: Session }) {
  const [participantRef, setParticipantRef] = useState('');
  const [result, setResult] = useState<{ participantRef: string; password: string } | null>(null);

  async function provision(e: React.FormEvent) {
    e.preventDefault();
    const auth = 'Basic ' + btoa(`${session.stewardId}:${session.password}`);
    const response = await fetch(`${STEWARD_API_BASE_URL}/participants/${encodeURIComponent(participantRef)}/provision`, {
      method: 'POST',
      headers: { authorization: auth }
    });
    setResult(await response.json() as { participantRef: string; password: string });
  }

  return (
    <div>
      <h2>Provision a participant</h2>
      <form onSubmit={provision}>
        <label>
          Participant ID
          <input value={participantRef} onChange={(e) => setParticipantRef(e.target.value)} required />
        </label>
        <button type="submit">Provision</button>
      </form>
      {result && (
        <p>
          Give these to the participant once, out of band -- they will not be shown again:
          ID <code>{result.participantRef}</code>, password <code>{result.password}</code>
        </p>
      )}
    </div>
  );
}
```

- [ ] **Step 8: Install and build**

Run: `npm install && npm run build --workspace=@benefits/steward-portal`
Expected: builds without type errors.

- [ ] **Step 9: Manual click-through** (Gate 4 for this app)

Run: `npm run dev --workspace=@benefits/steward-portal` (requires CouchDB and a provisioned steward account, Task 10). Log in, view the queue (empty is fine if no release request has been created yet via Task 7's endpoint -- create one first via `curl` against `POST /release-requests` to have something to review), co-sign or veto it, confirm the queue reflects the new state on reload, then provision a test participant and confirm a password is returned.

- [ ] **Step 10: Commit**

```bash
git add apps/steward-portal
git commit -m "feat(steward-portal): review queue and participant provisioning"
```
