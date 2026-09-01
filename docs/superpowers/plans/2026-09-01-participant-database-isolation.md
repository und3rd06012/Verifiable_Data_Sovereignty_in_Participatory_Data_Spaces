# Participant Database Isolation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every participant their own private CouchDB database instead of raw credentials to the shared `benefits` database, closing the read-boundary gap phase 6's final whole-branch review found, without a gateway and without changing what the shared database's existing readers (audit chain, contribution source, privacy pipeline) see.

**Architecture:** `StewardResource.provisionParticipant` gains two steps beyond creating the CouchDB user: create a private `participant-<ref>` database (locked to that one user via `_security`, gated by the same participant-identity `validate_doc_update` check the shared database already uses), and configure a continuous, admin-mediated CouchDB replication job feeding it into `benefits`. Both client apps repoint their remote sync URL from `benefits` to their own `participant-<ref>` database — one line each.

**Tech Stack:** Java 21 (EDC connector, `java.net.http.HttpClient`, Jackson `ObjectMapper`), CouchDB 3.x's native `_security` and `_replicator` HTTP APIs, Node/esbuild (already-existing `packages/consent-couchdb` tooling, extended with one export script), TypeScript (`apps/participant-web`), Dart (`apps/participant-mobile`).

**Spec:** docs/superpowers/specs/2026-09-01-participant-database-isolation-design.md (amends docs/superpowers/specs/2026-09-01-benefits-interfaces-design.md §2/§3 — read both)

## Global Constraints

- No unified API gateway; single shared CouchDB database remains the only thing `CouchDbAuditStore`/`CouchDbContributionSource`/the privacy pipeline read (spec §2, §7).
- Every CouchDB Mango selector built via `ObjectMapper`, never string interpolation (inherited from the parent interfaces plan; N/A to this plan's direct-PUT-of-fixed-JSON code, noted for completeness).
- Every CouchDB I/O exception becomes `EdcException`; every `InterruptedException` re-interrupts the thread then rethrows as `EdcException` (established pattern, `CouchDbUserProvisioner` is the reference example).
- No emojis, no em-dashes, anywhere in code, docs, or commit messages.
- No LLM/AI-tooling attribution fingerprints in any commit.
- Verify CouchDB behavior live against a real instance rather than trusting documentation — established practice throughout phase 6's review process, and explicitly required here for the `_replicator` API and whether replication invokes the target database's `validate_doc_update` (spec §5, §8).
- `participantRef` values provisioned through this endpoint must be valid CouchDB database name components once this plan lands (Task 4 tightens the existing validation regex for this reason — see Task 4 for the exact new pattern and why).

---

### Task 1: Export the design-doc bundle as a checked-in Java resource

**Files:**
- Create: `packages/consent-couchdb/scripts/export-design-doc.mjs`
- Modify: `packages/consent-couchdb/package.json` (add an npm script)
- Create: `connector/extensions/benefits-privacy/src/main/resources/consent-design-doc.json` (generated output, checked in)
- Test: `packages/consent-couchdb/test/design-doc-resource.test.ts`

**Interfaces:**
- Consumes: `buildDesignDoc()` from `packages/consent-couchdb/src/build-design-doc.ts` (already exists, unchanged — returns `{_id: string, validate_doc_update: string}`).
- Produces: `connector/extensions/benefits-privacy/src/main/resources/consent-design-doc.json`, a static JSON file with the same `{_id, validate_doc_update}` shape, readable as a Java classpath resource at `/consent-design-doc.json`. Task 2 consumes this.

`buildDesignDoc()` calls `esbuild.buildSync` at call time — it only works inside a Node process. The Java connector cannot invoke it at runtime (no Node/esbuild dependency in the connector, and provisioning must be a single synchronous HTTP call). So its output gets exported once, as a build artifact checked into git, and re-exported by hand whenever `packages/consent-core/src/evaluate.ts` or `build-design-doc.ts` changes. A same-language test (Node/vitest, no CouchDB or Java needed) catches drift between the checked-in file and what `buildDesignDoc()` currently produces.

- [ ] **Step 1: Write the export script**

```javascript
// packages/consent-couchdb/scripts/export-design-doc.mjs
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { buildDesignDoc } from '../dist/build-design-doc.js';

const here = dirname(fileURLToPath(import.meta.url));
const outPath = join(here, '../../../connector/extensions/benefits-privacy/src/main/resources/consent-design-doc.json');

const designDoc = buildDesignDoc();
writeFileSync(outPath, JSON.stringify(designDoc, null, 2) + '\n');
console.log(`wrote ${outPath}`);
```

- [ ] **Step 2: Add the npm script**

In `packages/consent-couchdb/package.json`, add to `"scripts"`:
```json
"export-design-doc": "npm run build && node scripts/export-design-doc.mjs"
```

- [ ] **Step 3: Run it and verify the output**

Run: `npm run export-design-doc --workspace=@benefits/consent-couchdb`
Expected: prints the output path; `connector/extensions/benefits-privacy/src/main/resources/consent-design-doc.json` now exists with `_id: "_design/consent"` and a `validate_doc_update` string field containing the bundled JS (same content `buildDesignDoc()` already produces for every other consumer of this function — `identity.integration.test.ts`, `demo-s1.sh`, etc.).

- [ ] **Step 4: Write the drift-detection test**

```typescript
// packages/consent-couchdb/test/design-doc-resource.test.ts
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { buildDesignDoc } from '../src/build-design-doc';

const here = dirname(fileURLToPath(import.meta.url));
const resourcePath = join(here, '../../../connector/extensions/benefits-privacy/src/main/resources/consent-design-doc.json');

describe('consent-design-doc.json (checked-in export for the Java connector)', () => {
  it('matches buildDesignDoc()\'s current output exactly', () => {
    const checkedIn = JSON.parse(readFileSync(resourcePath, 'utf-8'));
    const fresh = buildDesignDoc();
    expect(checkedIn).toEqual(fresh);
  });
});
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `npm test --workspace=@benefits/consent-couchdb`
Expected: PASS, including the new `design-doc-resource.test.ts` test. If it fails because `dist/build-design-doc.js` is stale, run `npm run build --workspace=@benefits/consent-couchdb` first — the export script's own `npm run build &&` prefix handles this for the export step, but the test reads `src/build-design-doc.ts` directly (via `vitest`'s TS support), so it always compares against current source, not a stale `dist/`.

- [ ] **Step 6: Commit**

```bash
git add packages/consent-couchdb/scripts/export-design-doc.mjs packages/consent-couchdb/package.json \
  connector/extensions/benefits-privacy/src/main/resources/consent-design-doc.json \
  packages/consent-couchdb/test/design-doc-resource.test.ts
git commit -m "feat(consent-couchdb): export the design-doc bundle as a checked-in Java resource"
```

---

### Task 2: `CouchDbParticipantDatabaseProvisioner`

**Files:**
- Create: `connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/CouchDbParticipantDatabaseProvisioner.java`
- Test: `connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/CouchDbParticipantDatabaseProvisionerIntegrationTest.java`

**Interfaces:**
- Consumes: `connector/extensions/benefits-privacy/src/main/resources/consent-design-doc.json` (Task 1) as a classpath resource.
- Produces: `CouchDbParticipantDatabaseProvisioner` — constructor `(String baseUrl, String adminUser, String adminPassword, ObjectMapper mapper)`; `String databaseNameFor(String participantRef)` returning `"participant-" + participantRef`; `Outcome provision(String participantRef)` returning `CREATED` or `ALREADY_EXISTS`, throwing `EdcException` on any I/O failure. Task 4 consumes both methods.

Creates a participant's own private CouchDB database, locks its `_security` to exactly that one participant, and installs the same identity-check design document the shared database already carries — installed here per-database instead of once, so it still stops a participant's document from *claiming* a different `participantRef` even though `_security` already stops anyone else from writing here at all.

- [ ] **Step 1: Write the class**

```java
package org.benefits.policy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.edc.spi.EdcException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

public class CouchDbParticipantDatabaseProvisioner {

    public enum Outcome { CREATED, ALREADY_EXISTS }

    private final String baseUrl;
    private final String adminAuthHeader;
    private final ObjectMapper mapper;
    private final JsonNode designDoc;
    private final HttpClient client = HttpClient.newHttpClient();

    public CouchDbParticipantDatabaseProvisioner(String baseUrl, String adminUser, String adminPassword, ObjectMapper mapper) {
        this.baseUrl = baseUrl;
        this.adminAuthHeader = "Basic " + Base64.getEncoder().encodeToString((adminUser + ":" + adminPassword).getBytes());
        this.mapper = mapper;
        this.designDoc = loadDesignDoc(mapper);
    }

    private static JsonNode loadDesignDoc(ObjectMapper mapper) {
        try (InputStream in = CouchDbParticipantDatabaseProvisioner.class.getResourceAsStream("/consent-design-doc.json")) {
            if (in == null) {
                throw new EdcException("consent-design-doc.json resource not found on classpath");
            }
            return mapper.readTree(in);
        } catch (IOException e) {
            throw new EdcException("failed to load consent-design-doc.json", e);
        }
    }

    public String databaseNameFor(String participantRef) {
        return "participant-" + participantRef;
    }

    public Outcome provision(String participantRef) {
        var dbName = databaseNameFor(participantRef);
        var outcome = createDatabase(dbName);
        setSecurity(dbName, participantRef);
        installDesignDoc(dbName);
        return outcome;
    }

    private Outcome createDatabase(String dbName) {
        var response = send(HttpRequest.newBuilder(URI.create(baseUrl + "/" + dbName))
                .header("authorization", adminAuthHeader)
                .PUT(HttpRequest.BodyPublishers.noBody()));
        if (response.statusCode() == 201) {
            return Outcome.CREATED;
        }
        if (response.statusCode() == 412) {
            return Outcome.ALREADY_EXISTS;
        }
        throw new EdcException("failed to create participant database " + dbName + ": "
                + response.statusCode() + " " + response.body());
    }

    private void setSecurity(String dbName, String participantRef) {
        var body = mapper.createObjectNode();
        var members = body.putObject("members");
        members.putArray("names").add(participantRef);
        members.putArray("roles");
        var admins = body.putObject("admins");
        admins.putArray("names");
        admins.putArray("roles").add("_admin");
        var response = send(HttpRequest.newBuilder(URI.create(baseUrl + "/" + dbName + "/_security"))
                .header("authorization", adminAuthHeader).header("content-type", "application/json")
                .PUT(bodyOf(body)));
        if (response.statusCode() != 200) {
            throw new EdcException("failed to set _security on " + dbName + ": "
                    + response.statusCode() + " " + response.body());
        }
    }

    private void installDesignDoc(String dbName) {
        var response = send(HttpRequest.newBuilder(URI.create(baseUrl + "/" + dbName + "/" + designDoc.get("_id").asText()))
                .header("authorization", adminAuthHeader).header("content-type", "application/json")
                .PUT(bodyOf(designDoc)));
        // 409 here means the identical design doc is already installed from a prior
        // provisioning call -- its content never varies per participant, so this is a
        // no-op, not a failure (unlike CouchDbUserProvisioner's password case, there is
        // no distinct value at risk of being silently un-applied).
        if (response.statusCode() != 200 && response.statusCode() != 201 && response.statusCode() != 409) {
            throw new EdcException("failed to install design doc on " + dbName + ": "
                    + response.statusCode() + " " + response.body());
        }
    }

    private HttpRequest.BodyPublisher bodyOf(JsonNode body) {
        try {
            return HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new EdcException("failed to serialize request body", e);
        }
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new EdcException("CouchDB request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdcException("interrupted during CouchDB request", e);
        }
    }
}
```

- [ ] **Step 2: Write the integration test**

```java
package org.benefits.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class CouchDbParticipantDatabaseProvisionerIntegrationTest {

    private static final String BASE_URL = "http://localhost:5984";
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void createsAnIsolatedDatabaseWithTheIdentityGateInstalled() throws Exception {
        var participantRef = "p-dbtest-" + System.nanoTime();
        var provisioner = new CouchDbParticipantDatabaseProvisioner(BASE_URL, "admin", "benefits", mapper);

        var outcome = provisioner.provision(participantRef);
        assertThat(outcome).isEqualTo(CouchDbParticipantDatabaseProvisioner.Outcome.CREATED);
        // provision() only creates the database/_security/design-doc -- the CouchDB user
        // itself is CouchDbUserProvisioner's job (Task 4 wires both together). Provision
        // one here directly so this test can authenticate as the real participant, not admin.
        provisionCouchDbUser(participantRef, "pw-own");

        var dbName = provisioner.databaseNameFor(participantRef);
        var ownAuth = "Basic " + Base64.getEncoder().encodeToString((participantRef + ":pw-own").getBytes());

        // The participant can reach their own database, with their own credentials.
        var ownRead = http.send(HttpRequest.newBuilder(URI.create(BASE_URL + "/" + dbName))
                .header("authorization", ownAuth).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(ownRead.statusCode()).isEqualTo(200);

        // A different participant cannot read this one's database.
        var otherRef = "p-dbtest-other-" + System.nanoTime();
        provisionCouchDbUser(otherRef, "otherpw");
        var otherAuth = "Basic " + Base64.getEncoder().encodeToString((otherRef + ":otherpw").getBytes());
        var otherRead = http.send(HttpRequest.newBuilder(URI.create(BASE_URL + "/" + dbName))
                .header("authorization", otherAuth).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(otherRead.statusCode()).isEqualTo(403);

        // Re-provisioning the same participant is idempotent.
        var second = provisioner.provision(participantRef);
        assertThat(second).isEqualTo(CouchDbParticipantDatabaseProvisioner.Outcome.ALREADY_EXISTS);
    }

    private void provisionCouchDbUser(String name, String password) throws Exception {
        var body = mapper.createObjectNode();
        body.put("name", name).put("password", password).put("type", "user");
        body.putArray("roles");
        http.send(HttpRequest.newBuilder(URI.create(BASE_URL + "/_users/org.couchdb.user:" + name))
                .header("authorization", adminAuth()).header("content-type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String adminAuth() {
        return "Basic " + Base64.getEncoder().encodeToString("admin:benefits".getBytes());
    }
}
```

- [ ] **Step 3: Run against real CouchDB and iterate on any real behavior mismatch**

Ensure CouchDB is running (`tools/lib.sh`'s `ensure_couchdb`, or `docker compose -f deploy/docker-compose.yml up -d couchdb`).
Run: `./gradlew :connector:extensions:benefits-privacy:test --tests CouchDbParticipantDatabaseProvisionerIntegrationTest`
Expected: PASS. If any status code assumption in Step 1 is wrong (this project's established practice is to verify CouchDB behavior live, not trust documentation) — e.g. database creation returns something other than 201/412, or `_security` behaves differently — fix the implementation to match what CouchDB actually does, not the other way around.

- [ ] **Step 4: Add a forged-identity test (spec §5's primary boundary)**

```java
    @Test
    void aParticipantCannotWriteADocumentClaimingSomeoneElsesIdentity() throws Exception {
        var participantRef = "p-forge-test-" + System.nanoTime();
        var provisioner = new CouchDbParticipantDatabaseProvisioner(BASE_URL, "admin", "benefits", mapper);
        provisioner.provision(participantRef);
        provisionCouchDbUser(participantRef, "pw-forge");
        var dbName = provisioner.databaseNameFor(participantRef);
        var ownAuth = "Basic " + Base64.getEncoder().encodeToString((participantRef + ":pw-forge").getBytes());

        var forged = mapper.createObjectNode();
        forged.put("type", "contribution").put("participantRef", "someone-else");
        var response = http.send(HttpRequest.newBuilder(URI.create(BASE_URL + "/" + dbName + "/forged-doc"))
                .header("authorization", ownAuth).header("content-type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(forged))).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(403);
    }
```

Run: `./gradlew :connector:extensions:benefits-privacy:test --tests CouchDbParticipantDatabaseProvisionerIntegrationTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/CouchDbParticipantDatabaseProvisioner.java \
  connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/CouchDbParticipantDatabaseProvisionerIntegrationTest.java
git commit -m "feat(benefits-privacy): provision an isolated, identity-gated database per participant"
```

---

### Task 3: `CouchDbReplicationProvisioner`

**Files:**
- Create: `connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/CouchDbReplicationProvisioner.java`
- Test: `connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/CouchDbReplicationProvisionerIntegrationTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks in this plan (independent of Task 2's database shape — it replicates between any two named databases).
- Produces: `CouchDbReplicationProvisioner` — constructor `(String baseUrl, String adminUser, String adminPassword, ObjectMapper mapper)`; `void ensureReplication(String sourceDb, String targetDb)`, idempotent, throwing `EdcException` on failure. Task 4 consumes this.

Configures a continuous, one-way CouchDB replication job via the `_replicator` database, admin-credentialed on both source and target, so `CouchDbAuditStore`/`CouchDbContributionSource` keep seeing every participant's writes land in the single shared `benefits` database without the participant ever holding credentials to it.

- [ ] **Step 1: Write the class**

```java
package org.benefits.policy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.edc.spi.EdcException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

public class CouchDbReplicationProvisioner {

    private final String baseUrl;
    private final String adminAuthHeader;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();

    public CouchDbReplicationProvisioner(String baseUrl, String adminUser, String adminPassword, ObjectMapper mapper) {
        this.baseUrl = baseUrl;
        this.adminAuthHeader = "Basic " + Base64.getEncoder().encodeToString((adminUser + ":" + adminPassword).getBytes());
        this.mapper = mapper;
    }

    public void ensureReplication(String sourceDb, String targetDb) {
        var jobId = "replicate-" + sourceDb + "-to-" + targetDb;
        var body = mapper.createObjectNode();
        var source = body.putObject("source");
        source.put("url", baseUrl + "/" + sourceDb);
        source.putObject("headers").put("Authorization", adminAuthHeader);
        var target = body.putObject("target");
        target.put("url", baseUrl + "/" + targetDb);
        target.putObject("headers").put("Authorization", adminAuthHeader);
        body.put("continuous", true);
        body.put("owner", "admin");
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/_replicator/" + jobId))
                    .header("authorization", adminAuthHeader).header("content-type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            // Exact accepted status codes are verified live in the integration test below --
            // CouchDB's _replicator database is processed asynchronously by the replicator
            // manager, and this project verifies such behavior against a real instance rather
            // than assuming from documentation.
            if (response.statusCode() != 201 && response.statusCode() != 202 && response.statusCode() != 409) {
                throw new EdcException("failed to create replication job " + jobId + ": "
                        + response.statusCode() + " " + response.body());
            }
        } catch (JsonProcessingException e) {
            throw new EdcException("failed to serialize replication job body", e);
        } catch (IOException e) {
            throw new EdcException("failed to create replication job " + jobId, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdcException("interrupted creating replication job " + jobId, e);
        }
    }
}
```

- [ ] **Step 2: Write the integration test proving replication actually lands a write**

```java
package org.benefits.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class CouchDbReplicationProvisionerIntegrationTest {

    private static final String BASE_URL = "http://localhost:5984";
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aDocumentWrittenToTheSourceEventuallyAppearsInTheTarget() throws Exception {
        var suffix = System.nanoTime();
        var sourceDb = "replicate-source-test-" + suffix;
        var targetDb = "replicate-target-test-" + suffix;
        createDatabase(sourceDb);
        createDatabase(targetDb);

        new CouchDbReplicationProvisioner(BASE_URL, "admin", "benefits", mapper).ensureReplication(sourceDb, targetDb);

        var docId = "doc-" + suffix;
        var body = mapper.createObjectNode().put("marker", "replication-test");
        var put = http.send(HttpRequest.newBuilder(URI.create(BASE_URL + "/" + sourceDb + "/" + docId))
                .header("authorization", adminAuth()).header("content-type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(put.statusCode()).isEqualTo(201);

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            var get = http.send(HttpRequest.newBuilder(URI.create(BASE_URL + "/" + targetDb + "/" + docId))
                    .header("authorization", adminAuth()).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(get.statusCode()).isEqualTo(200);
            assertThat(get.body()).contains("replication-test");
        });
    }

    private void createDatabase(String dbName) throws Exception {
        http.send(HttpRequest.newBuilder(URI.create(BASE_URL + "/" + dbName))
                .header("authorization", adminAuth()).PUT(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String adminAuth() {
        return "Basic " + Base64.getEncoder().encodeToString("admin:benefits".getBytes());
    }
}
```

Check whether this module already has `awaitility` as a test dependency (grep `build.gradle.kts` for `awaitility`); if not, either add it (`testImplementation("org.awaitility:awaitility:<verify current version against Maven Central>")`) or replace the `await().atMost(...)` block with a plain manual retry loop (`for` loop with `Thread.sleep(500)` up to 30 iterations, asserting inside) to avoid adding a new test dependency for one test — prefer the manual loop if `awaitility` isn't already present, to keep this change minimal.

- [ ] **Step 3: Run against real CouchDB and iterate on any real behavior mismatch**

Run: `./gradlew :connector:extensions:benefits-privacy:test --tests CouchDbReplicationProvisionerIntegrationTest`
Expected: PASS within the timeout. If replication doesn't start or the job document is rejected, inspect `GET /_scheduler/jobs` and `GET /_replicator/<jobId>` on the live CouchDB instance to see the actual error CouchDB reports, and adjust the job document's shape (this is exactly the kind of CouchDB behavior this project verifies live rather than assumes).

- [ ] **Step 4: Commit**

```bash
git add connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/CouchDbReplicationProvisioner.java \
  connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/CouchDbReplicationProvisionerIntegrationTest.java
git commit -m "feat(benefits-privacy): admin-mediated continuous replication into the shared database"
```

---

### Task 4: Wire both provisioners into `StewardResource.provisionParticipant`

**Files:**
- Modify: `connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/StewardResource.java`
- Modify: `connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/BenefitsPrivacyExtension.java`
- Modify: `connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/StewardResourceProvisioningIntegrationTest.java`
- Modify: `connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/StewardResourceTest.java`
- Modify: `connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/StewardResourceConcurrencyIntegrationTest.java`

**Interfaces:**
- Consumes: `CouchDbParticipantDatabaseProvisioner` (Task 2), `CouchDbReplicationProvisioner` (Task 3).
- Produces: `StewardResource`'s constructor gains two parameters (see Step 1). Task 6 (live click-through) exercises this end to end; no other task consumes it directly.

`StewardResource`'s existing `SAFE_PARTICIPANT_REF` pattern (`^[a-zA-Z0-9_.@-]+$`) allows characters CouchDB database names reject (uppercase letters, `.`, `@`) — CouchDB database names must match `^[a-z][a-z0-9_$()+/-]*$`. Now that provisioning always creates a database named `participant-<ref>`, the pattern is tightened to a safe subset of both: `^[a-z][a-z0-9_-]*$` (lowercase letter first, then lowercase letters, digits, `_`, or `-`). This is a real, disclosed behavior change from the already-reviewed validation this plan builds on top of — necessary because a `participantRef` that fails the new check can no longer be provisioned successfully at all, not an incidental tightening.

- [ ] **Step 1: Update `StewardResource`**

In `StewardResource.java`, change:
```java
    private static final Pattern SAFE_PARTICIPANT_REF = Pattern.compile("^[a-zA-Z0-9_.@-]+$");
```
to:
```java
    // Tightened to a safe subset of CouchDB's own database-name rules
    // (^[a-z][a-z0-9_$()+/-]*$) now that provisioning always creates a
    // "participant-<ref>" database -- a ref that fails this check could
    // never be provisioned successfully.
    private static final Pattern SAFE_PARTICIPANT_REF = Pattern.compile("^[a-z][a-z0-9_-]*$");
```

Add two fields and constructor parameters, and update `provisionParticipant`:
```java
    private final StewardAuthorizationStore authorizationStore;
    private final ReleaseRequestStore requestStore;
    private final StewardAuthenticator authenticator;
    private final CouchDbUserProvisioner userProvisioner;
    private final CouchDbParticipantDatabaseProvisioner databaseProvisioner;
    private final CouchDbReplicationProvisioner replicationProvisioner;

    public StewardResource(StewardAuthorizationStore authorizationStore, ReleaseRequestStore requestStore,
                            StewardAuthenticator authenticator, CouchDbUserProvisioner userProvisioner,
                            CouchDbParticipantDatabaseProvisioner databaseProvisioner,
                            CouchDbReplicationProvisioner replicationProvisioner) {
        this.authorizationStore = authorizationStore;
        this.requestStore = requestStore;
        this.authenticator = authenticator;
        this.userProvisioner = userProvisioner;
        this.databaseProvisioner = databaseProvisioner;
        this.replicationProvisioner = replicationProvisioner;
    }
```

(Rename the existing `provisioner` field/parameter to `userProvisioner` throughout the class for clarity now that there are three provisioners — update every reference, including inside `provisionParticipant`.)

Replace `provisionParticipant`'s body:
```java
    @POST
    @Path("/participants/{participantRef}/provision")
    @Produces(MediaType.APPLICATION_JSON)
    public Response provisionParticipant(@PathParam("participantRef") String participantRef,
                                          @HeaderParam("Authorization") String authorizationHeader) {
        var steward = authenticator.authenticateSteward(authorizationHeader);
        if (steward == null) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        if (!SAFE_PARTICIPANT_REF.matcher(participantRef).matches()) {
            return Response.status(400).entity(new ErrorBody(
                    "participantRef must start with a lowercase letter and contain only lowercase letters, digits, '-', or '_'")).build();
        }
        var password = generatePassword();
        CouchDbUserProvisioner.Outcome userOutcome;
        try {
            userOutcome = userProvisioner.provision(participantRef, password, List.of());
        } catch (EdcException e) {
            return Response.status(502).entity(new ErrorBody("failed to provision CouchDB user: " + e.getMessage())).build();
        }
        try {
            databaseProvisioner.provision(participantRef);
            replicationProvisioner.ensureReplication(databaseProvisioner.databaseNameFor(participantRef), "benefits");
        } catch (EdcException e) {
            return Response.status(502).entity(new ErrorBody(
                    "CouchDB user provisioned but database/replication setup failed: " + e.getMessage())).build();
        }
        if (userOutcome == CouchDbUserProvisioner.Outcome.ALREADY_EXISTS) {
            return Response.status(200).entity(new ProvisionStatus(participantRef,
                    "participant is already provisioned; no new password was issued")).build();
        }
        return Response.status(201).entity(new ProvisionedCredentials(participantRef, password)).build();
    }
```

- [ ] **Step 2: Update `BenefitsPrivacyExtension`**

In the `benefits.stewardApi.enabled` block, extend the `StewardResource` construction:
```java
        if (Boolean.parseBoolean(context.getSetting("benefits.stewardApi.enabled", "false"))) {
            var couchdbUrl = context.getSetting("benefits.couchdb.url", "http://localhost:5984");
            var couchUser = context.getSetting("benefits.couchdb.user", "admin");
            var couchPassword = context.getSetting("benefits.couchdb.password", "");
            var stewardAuthenticator = new CouchDbStewardAuthenticator(couchdbUrl, new com.fasterxml.jackson.databind.ObjectMapper());
            var userProvisioner = new CouchDbUserProvisioner(couchdbUrl, couchUser, couchPassword,
                    new com.fasterxml.jackson.databind.ObjectMapper());
            var databaseProvisioner = new CouchDbParticipantDatabaseProvisioner(couchdbUrl, couchUser, couchPassword,
                    new com.fasterxml.jackson.databind.ObjectMapper());
            var replicationProvisioner = new CouchDbReplicationProvisioner(couchdbUrl, couchUser, couchPassword,
                    new com.fasterxml.jackson.databind.ObjectMapper());
            var stewardResource = new StewardResource(stewardAuthorizationStore, releaseRequestStore,
                    stewardAuthenticator, userProvisioner, databaseProvisioner, replicationProvisioner);

            var stewardPort = context.getSetting("web.http.steward.port", 19199);
            var stewardPath = context.getSetting("web.http.steward.path", "/steward");
            portMappingRegistry.register(new PortMapping("steward", stewardPort, stewardPath));
            webService.registerResource("steward", stewardResource);
        }
```

- [ ] **Step 3: Update the existing provisioning test's constructor calls**

`StewardResourceProvisioningIntegrationTest.java` constructs `StewardResource` directly and needs the two new constructor arguments. It also needs real CouchDB (already implied by its class name and existing use of `CouchDbUserProvisioner` against `http://localhost:5984`), so this test now also exercises database/replication provisioning, not just the user:

```java
    @Test
    void reProvisioningAnExistingParticipantDoesNotClaimAFalsePassword() {
        var participantRef = "p-provision-test-" + System.nanoTime();
        var mapper = new ObjectMapper();
        var userProvisioner = new CouchDbUserProvisioner("http://localhost:5984", "admin", "benefits", mapper);
        var databaseProvisioner = new CouchDbParticipantDatabaseProvisioner("http://localhost:5984", "admin", "benefits", mapper);
        var replicationProvisioner = new CouchDbReplicationProvisioner("http://localhost:5984", "admin", "benefits", mapper);
        var resource = new StewardResource(new InMemoryStewardAuthorizationStore(), new InMemoryReleaseRequestStore(),
                ALWAYS_STEWARD_S1, userProvisioner, databaseProvisioner, replicationProvisioner);

        var first = resource.provisionParticipant(participantRef, "Basic irrelevant");
        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(first.getEntity()).isInstanceOf(StewardResource.ProvisionedCredentials.class);
        var firstCredentials = (StewardResource.ProvisionedCredentials) first.getEntity();
        assertThat(firstCredentials.password()).isNotBlank();

        var second = resource.provisionParticipant(participantRef, "Basic irrelevant");
        assertThat(second.getStatus()).isEqualTo(200);
        assertThat(second.getEntity()).isInstanceOf(StewardResource.ProvisionStatus.class);
    }
```

(This requires the `benefits` database to already exist on the live CouchDB instance for the replication step to succeed — `tools/lib.sh`'s `ensure_couchdb`/`configure_couchdb` already creates it, so running this test through the same setup path other integration tests in this module already assume is sufficient; no new setup code needed in the test itself.)

- [ ] **Step 4: Run the full module test suite**

Ensure CouchDB is running with `benefits` already created (`tools/lib.sh`'s `ensure_couchdb`).
Run: `./gradlew :connector:extensions:benefits-privacy:test`
Expected: BUILD SUCCESSFUL, all tests pass including the updated `StewardResourceProvisioningIntegrationTest` and the two new provisioner tests from Tasks 2-3.

- [ ] **Step 5: Update the three other test files that construct `StewardResource` directly**

None of these exercise `provisionParticipant` (they pass `null` for the provisioner today since they only test review/coSign/veto/appeal/queue), so add two more `null` arguments each, matching the existing pattern:

`StewardResourceTest.java:24` and `:46` — change
```java
new StewardResource(authorizationStore, requestStore, ALWAYS_STEWARD_S1, null);
```
and
```java
new StewardResource(authorizationStore, requestStore, ALWAYS_UNAUTHENTICATED, null);
```
to
```java
new StewardResource(authorizationStore, requestStore, ALWAYS_STEWARD_S1, null, null, null);
```
and
```java
new StewardResource(authorizationStore, requestStore, ALWAYS_UNAUTHENTICATED, null, null, null);
```

`StewardResourceConcurrencyIntegrationTest.java:32` and `:33` — same change, `null` becomes `null, null, null` in both `new StewardResource(...)` calls.

- [ ] **Step 6: Compile and run the full connector to confirm nothing else breaks**

Run: `./gradlew compileJava compileTestJava`
Expected: BUILD SUCCESSFUL. Then re-run Step 4's full module test command to confirm `StewardResourceTest` and `StewardResourceConcurrencyIntegrationTest` still pass unchanged (they don't touch the new provisioners, so their behavior should be identical).

- [ ] **Step 7: Commit**

```bash
git add connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/StewardResource.java \
  connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/BenefitsPrivacyExtension.java \
  connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/StewardResourceProvisioningIntegrationTest.java \
  connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/StewardResourceTest.java \
  connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/StewardResourceConcurrencyIntegrationTest.java
git commit -m "feat(benefits-privacy): provision participants with an isolated database and replication, not just a CouchDB user"
```

---

### Task 5: Repoint both client apps at their own database

**Files:**
- Modify: `apps/participant-web/src/ConsentCards.tsx`
- Modify: `apps/participant-mobile/lib/session.dart`

**Interfaces:**
- Consumes: nothing new — both files already construct a database URL from `session`/props; this task only changes which database name they target.
- Produces: nothing later tasks depend on directly; Task 6 exercises the result live.

- [ ] **Step 1: `apps/participant-web/src/ConsentCards.tsx`**

Change:
```typescript
  const remoteUrl = COUCHDB_BASE_URL.replace(
    '://', `://${encodeURIComponent(session.participantRef)}:${encodeURIComponent(session.password)}@`
  ) + '/benefits';
```
to:
```typescript
  const remoteUrl = COUCHDB_BASE_URL.replace(
    '://', `://${encodeURIComponent(session.participantRef)}:${encodeURIComponent(session.password)}@`
  ) + '/participant-' + session.participantRef;
```

No other change to this file. `resolveConflicts()` (`packages/consent-web-client/src/sync.ts`) needs no change at all — it already reads whatever `remoteUrl` it's given, and reading a participant's own database is legitimate once this repoints it there.

- [ ] **Step 2: `apps/participant-mobile/lib/session.dart`**

Change:
```dart
  String get remoteDatabaseUrl => '$couchDbBaseUrl/benefits';
```
to:
```dart
  String get remoteDatabaseUrl => '$couchDbBaseUrl/participant-$participantRef';
```

- [ ] **Step 3: Verify both apps still build**

Run: `npm run build --workspace=@benefits/participant-web`
Expected: builds clean, no type errors (this is a string-literal change only).

Run: `flutter analyze` from `apps/participant-mobile/`
Expected: clean, no warnings.

Run: `flutter test` from `apps/participant-mobile/`
Expected: existing suite (Task 13/14's tests) still passes — none of them assert on the literal database name, per the existing test files' contents, but confirm this rather than assume it.

- [ ] **Step 4: Commit**

```bash
git add apps/participant-web/src/ConsentCards.tsx apps/participant-mobile/lib/session.dart
git commit -m "fix(participant-web,participant-mobile): sync to the participant's own database, not the shared one"
```

---

### Task 6: Live end-to-end verification

**Files:** none (verification only — no source changes expected; if verification surfaces a real defect, fix it in the file it belongs to and note the deviation in the report).

**Interfaces:**
- Consumes: everything from Tasks 1-5.
- Produces: nothing — this is the plan's proof that the fix works, mirroring the original interfaces plan's Task 15 (the last task being a live-verified deliverable, not new source).

- [ ] **Step 1: Stand up the full stack**

Reuse the exact recipe from `.superpowers/sdd/2026-09-01-benefits-interfaces/task-15-report.md` and the final fix wave's report (`final-fix-wave-report.md`) for building the connector jar, starting CouchDB via `tools/lib.sh`, and the `-D` flags needed (`benefits.releaseRequests.enabled=true`, `benefits.stewardApi.enabled=true`, `benefits.privacy.persistence.enabled=true`, `benefits.steward.persistence.enabled=true` — the fourth flag `provider.properties`'s comment documents per the final fix wave's I5 fix).

- [ ] **Step 2: Provision two participants via the steward portal, live in a browser**

Using Chrome DevTools MCP tools (as prior live click-throughs in this branch have done): log into `apps/steward-portal`, provision two participants (e.g. `p-verify-1`, `p-verify-2` — both must match the tightened `^[a-z][a-z0-9_-]*$` pattern from Task 4). Confirm each provisioning call succeeds and returns a password.

- [ ] **Step 3: Confirm database isolation directly against CouchDB**

Via `curl`: confirm `GET http://p-verify-1:<their-password>@localhost:5984/benefits` returns 403 (no access to the shared database). Confirm `GET http://p-verify-1:<their-password>@localhost:5984/participant-p-verify-1` returns 200. Confirm `GET http://p-verify-1:<their-password>@localhost:5984/participant-p-verify-2` returns 403 (cannot read the other participant's database).

- [ ] **Step 4: Run the participant-web click-through, live, end to end**

Start `apps/participant-web` (`npm run dev`), log in as `p-verify-1`. Grant a consent, confirm it appears. Switch to Privacy Dashboard, confirm it shows there too (exercises the shared-`ParticipantClient` fix from an earlier round in this branch). Switch back to Consent, confirm it's still there. Click "Sync now" — this is the step that failed in the final fix wave's C1 verification, blocked by exactly the gap this plan fixes. Confirm it now reports a genuine pushed count with no error. Confirm via `curl` (admin credentials) that the document actually landed in the shared `benefits` database (proving replication worked, not just that the local push succeeded).

- [ ] **Step 5: Run the participant-mobile click-through**

If `apps/participant-mobile` has a way to run in this environment (check whether Flutter platform scaffolding exists yet — if not, this step is blocked by a separate, already-known gap (I8 from the final review) and should be reported as such, not worked around): grant a consent, sync, and confirm via `curl` that it lands in `benefits` the same way.

- [ ] **Step 6: Report**

Write a report to a new file, `.superpowers/sdd/2026-09-01-participant-database-isolation/verification-report.md` (create the directory if the subagent-driven-development workspace script hasn't already), covering: what was verified live and its outcome, whether mobile's click-through was reachable or blocked by the platform-scaffolding gap, and any deviation from this plan's assumptions with what was changed to fix it.

- [ ] **Step 7: Commit any fixes made during verification**

If Step 4-5 surfaced a real defect requiring a code change, commit it separately with a clear message describing what was wrong and why. If verification was entirely clean, there is nothing to commit for this task beyond the report (reports live in the gitignored SDD workspace per this project's established convention, not in the main commit history).
