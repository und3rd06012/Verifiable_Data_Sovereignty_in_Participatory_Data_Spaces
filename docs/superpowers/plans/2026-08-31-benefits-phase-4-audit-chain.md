# BENEFITS Phase 4: Audit Chain Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the audit chain: a per-epoch Merkle tree over connector events, hash-chained epoch roots, an EDC extension that seals it from real negotiation and transfer events, a participant-facing proof endpoint, and the TypeScript verifier a participant device runs against that endpoint's response.

**Architecture:** A new EDC extension (`connector/extensions/benefits-audit`, package `org.benefits.audit`) subscribes to four confirmed EDC event types, appends an `AuditEntry` per event, and seals a Merkle-tree epoch every `EPOCH_SIZE` entries under a single-writer lock. Entries and epochs persist to CouchDB. A JAX-RS resource registered through EDC's `WebService` SPI serves one participant's own entries, proofs, and the epoch chain. A dependency-free TypeScript package (`packages/audit-verify`) recomputes inclusion and chain integrity from that response alone.

**Tech Stack:** Java 21, EDC 0.18.0 (pinned), CouchDB (Docker, already a project dependency), TypeScript 5.7.2 / vitest (matching `packages/consent-core`).

**Spec:** docs/superpowers/specs/2026-08-31-benefits-audit-chain-design.md

## Global Constraints

- EDC version pinned at exactly 0.18.0, matching every other module in this repo.
- `leafHash(entry) = SHA-256(0x00 || canonicalBytes(entry))`; `nodeHash(left, right) = SHA-256(0x01 || left || right)`, domain-separated, never duplicate a lone leaf, promote it unhashed instead.
- Escape rule for `canonicalBytes`, byte-identical to `KAnonymity.KEY_ESCAPE` in `benefits-privacy`: replace `\` with `\\`, then `|` with `\|`, applied to every joined field before joining with `|`.
- `prevEpochHash(epochId, merkleRoot, sealedAt) = SHA-256(escape(epochId) | escape(merkleRoot) | escape(sealedAt))`; the first epoch's `prevEpochHash` is the fixed constant `SHA-256("")`.
- Epoch sealing triggers at `EPOCH_SIZE` unsealed entries, default 50, configurable via `benefits.audit.epochSize`.
- Audit entries and epochs persist to CouchDB from the start, no in-memory-only store, unlike the phase 3 precedent.
- The participant proof endpoint is `GET /audit/{participantRef}`, unauthenticated (stated limitation, no device identity infrastructure exists yet).
- The TypeScript verifier package (`packages/audit-verify`) has no CouchDB or EDC dependency and no external hashing library, it uses the Web Crypto API (`crypto.subtle`, available in Node 22 and every evergreen browser).
- Cross-language hashing conformance vectors live in `conformance/audit-vectors/`, run by both a Java `@TestFactory` and a TypeScript `vitest` suite, following the existing `conformance/vectors/` pattern for consent semantics.

---

### Task 1: Module skeleton, gradle wiring, and the gate 0 probe

**Files:**
- Create: `connector/extensions/benefits-audit/build.gradle.kts`
- Create: `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/ProbeEventSubscriber.java`
- Create: `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/ProbeAuditResource.java`
- Create: `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/BenefitsAuditExtension.java`
- Modify: `settings.gradle.kts`
- Modify: `connector/runtime/build.gradle.kts`
- Modify: `connector/runtime/provider.properties`
- Modify: `gradle/libs.versions.toml`
- Create: `tools/probe-audit-events.sh`
- Modify: `tools/lib.sh`

**Interfaces:**
- Produces: confirmation, recorded in the task report, of (a) which EDC event classes actually fire for one negotiation and one transfer and in what order, (b) whether `EventRouter` dispatch is single- or multi-threaded per subscriber, (c) that a JAX-RS resource registered via `WebService.registerResource` on a new `web.http.audit.*` context is reachable over HTTP in this runtime. Later tasks in this plan build on all three answers being "yes, as expected", if any is "no", stop and report back before continuing; do not improvise a different mechanism without recording why.

This is a de-risking probe, in the same spirit as phase 0's constraint-function probe and phase 3's data-plane probe: prove the two EDC extension points this phase depends on (`EventRouter`, `WebService`) actually work in this specific runtime topology, with real HTTP traffic, before any real audit logic is built on top of them.

- [ ] **Step 1: Add the `web-spi` library alias**

Add this line to `gradle/libs.versions.toml`, in the `[libraries]` section, alphabetically after `edc-validator-data-address-http-data`:

```toml
edc-web-spi = { module = "org.eclipse.edc:web-spi", version.ref = "edc" }
```

`web-spi` provides `org.eclipse.edc.web.spi.WebService` and transitively brings `jakarta.ws.rs:jakarta.ws.rs-api`, confirmed present in the local Gradle cache at `org.eclipse.edc:web-spi:0.18.0`'s POM, no separate JAX-RS dependency is needed.

- [ ] **Step 2: Create the module's build file**

Create `connector/extensions/benefits-audit/build.gradle.kts`:

```kotlin
plugins {
    `java-library`
}

dependencies {
    api(libs.edc.control.plane.spi)
    api(libs.edc.web.spi)
    implementation(libs.edc.control.plane.core)
    implementation(project(":connector:extensions:benefits-policy"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.0")
}
```

`control-plane-spi` transitively depends on `core-spi` (which declares `EventRouter`/`EventSubscriber`/`Event`), `contract-spi` (the four negotiation event classes), and `transfer-spi` (the two transfer event classes), confirmed by inspecting `control-plane-spi-0.18.0.pom` directly; no separate aliases are needed for those three modules. `benefits-policy` is a dependency for `BenefitsNamespace.PURPOSE_KEY` and the `Constraints.siblingLiteral` helper, both used starting in Task 6.

- [ ] **Step 3: Register the module**

In `settings.gradle.kts`, add this line after the `benefits-privacy` include:

```kotlin
include(":connector:extensions:benefits-audit")
```

In `connector/runtime/build.gradle.kts`, add this line after the `benefits-privacy` implementation dependency:

```kotlin
    implementation(project(":connector:extensions:benefits-audit"))
```

- [ ] **Step 4: Add the audit web context to the provider's configuration**

In `connector/runtime/provider.properties`, add after the `signaling` context block:

```properties
web.http.audit.port=19197
web.http.audit.path=/audit
benefits.audit.enabled=true
```

This follows the existing `web.http.<name>.port` / `web.http.<name>.path` pattern already used for `management`, `protocol`, `control`, `signaling`, and `public` in this same file. `benefits.audit.enabled` is a new flag this phase introduces: both connectors boot off the same `connector.jar` and therefore load `BenefitsAuditExtension` regardless of which properties file started them, but only the provider (the community connector, per the parent spec's tier diagram) accumulates and serves audit entries, the consumer has no `web.http.audit.*` context configured at all, so the extension must not attempt to connect to CouchDB or register a web resource unless this flag is present and true. Task 6 reads it and no-ops otherwise. `consumer.properties` is deliberately left unchanged, its absence there means disabled, matching this file's own precedent of `benefits.requester.*` being consumer-only config with no equivalent key on the provider side.

- [ ] **Step 5: Add a probe event subscriber**

Create `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/ProbeEventSubscriber.java`:

```java
package org.benefits.audit;

import org.eclipse.edc.spi.event.Event;
import org.eclipse.edc.spi.event.EventSubscriber;
import org.eclipse.edc.spi.monitor.Monitor;

/**
 * Gate-0 probe only. Logs the class name and the calling thread of every event it
 * receives, so a real transfer through the running connector reveals which EDC event
 * types actually fire, in what order, and whether EventRouter dispatch is single- or
 * multi-threaded per subscriber. Deleted once BenefitsAuditEventListener (Task 6)
 * replaces it.
 */
public class ProbeEventSubscriber implements EventSubscriber {

    private final Monitor monitor;

    public ProbeEventSubscriber(Monitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public void on(org.eclipse.edc.spi.event.EventEnvelope<? extends Event> envelope) {
        monitor.info("AUDIT_PROBE event=" + envelope.getPayload().getClass().getName()
                + " thread=" + Thread.currentThread().getName());
    }
}
```

If `EventSubscriber.on` does not take an `EventEnvelope<? extends Event>` (confirm the exact signature by inspecting `org.eclipse.edc.spi.event.EventSubscriber` on the compile classpath, `javap` the class file inside the `core-spi-0.18.0.jar` extracted from the Gradle cache under `~/.gradle/caches/modules-2/files-2.1/org.eclipse.edc/core-spi/0.18.0/`), correct this file to match the real signature. Record whichever signature is real in the task report; Task 6 depends on it.

- [ ] **Step 6: Add a probe JAX-RS resource**

Create `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/ProbeAuditResource.java`:

```java
package org.benefits.audit;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/probe")
public class ProbeAuditResource {

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String probe() {
        return "AUDIT_PROBE_OK";
    }
}
```

- [ ] **Step 7: Register both probes in the extension**

Create `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/BenefitsAuditExtension.java`:

```java
package org.benefits.audit;

import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.spi.event.EventRouter;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.web.spi.WebService;

public class BenefitsAuditExtension implements ServiceExtension {

    @Inject
    private EventRouter eventRouter;
    @Inject
    private WebService webService;

    @Override
    public String name() {
        return "BENEFITS audit chain";
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        var monitor = context.getMonitor();
        if (!Boolean.parseBoolean(context.getSetting("benefits.audit.enabled", "false"))) {
            monitor.info("BENEFITS audit chain disabled (benefits.audit.enabled not set), skipping");
            return;
        }
        eventRouter.register(org.eclipse.edc.spi.event.Event.class, new ProbeEventSubscriber(monitor));
        webService.registerResource("audit", new ProbeAuditResource());
        monitor.info("BENEFITS audit chain: gate 0 probes registered");
    }
}
```

The probe reads the same `benefits.audit.enabled` flag Step 4 adds to `provider.properties` only, and does nothing on the consumer: both connectors boot the same `connector.jar` and therefore load this extension, but `consumer.properties` has no `web.http.audit.*` context configured, so attempting to register a web resource there is exactly the kind of thing that should not be attempted rather than discovered as a startup failure. This mirrors the same gate Task 6 applies to the real extension, applied here first since Task 1's probe script also starts a consumer connector.

If `EventRouter.register` does not accept `Event.class` as a wildcard registration for every event type (it may require one call per concrete event class, e.g. `eventRouter.register(ContractNegotiationFinalized.class, subscriber)`), register the subscriber once per one of the four concrete event classes named in the spec instead, and record which form compiles. If `webService.registerResource(String, Object)` does not match this signature, inspect `org.eclipse.edc.web.spi.WebService` (same jar as `WebServer`, extracted in the probe above) for the real one, EDC's own management-api module is the reference implementation for this registration pattern and is present on the build classpath transitively through `controlplane-base-bom`, so its source can be decompiled with `javap -p` for a working example if the obvious signature does not compile.

- [ ] **Step 8: Write and run the probe script**

Create `tools/probe-audit-events.sh`:

```bash
#!/usr/bin/env bash
# Gate 0: proves EventRouter delivers real negotiation/transfer events and that a
# JAX-RS resource registered outside EDC's own management-api module is reachable.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

WORK=/tmp/benefits-audit-probe
rm -rf "$WORK" && mkdir -p "$WORK"

cat > "$WORK/policy.json" <<'JSON'
{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@id": "benefits-policy",
  "policy": {
    "@context": [ "http://www.w3.org/ns/odrl.jsonld" ],
    "@type": "Set",
    "permission": [ { "action": "use" } ],
    "prohibition": [], "obligation": []
  }
}
JSON

trap 'stop_all' EXIT
stop_all
start_connector provider "$ROOT/connector/runtime/provider.properties" 19193
start_connector consumer "$ROOT/connector/runtime/consumer.properties" 29193

echo "--- probing audit web context ---"
curl -sf "http://localhost:19197/audit/probe"
echo
[ "$(curl -sf "http://localhost:19197/audit/probe")" = "AUDIT_PROBE_OK" ] \
  || { echo "audit probe resource unreachable" >&2; exit 1; }

curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/assets" -d '{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@id": "audit-probe-asset",
  "properties": { "name": "audit probe asset" },
  "dataAddress": { "type": "test" }
}' >/dev/null
curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/policydefinitions" -d @"$WORK/policy.json" >/dev/null
curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/contractdefinitions" -d '{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@id": "audit-probe-definition",
  "accessPolicyId": "benefits-policy",
  "contractPolicyId": "benefits-policy",
  "assetsSelector": []
}' >/dev/null

STATE=$(negotiate "$WORK/policy.json")
echo "negotiation: $STATE"
[ "$STATE" = "FINALIZED" ] || { echo "negotiation did not finalize" >&2; exit 1; }

echo "--- provider log lines mentioning AUDIT_PROBE ---"
grep "AUDIT_PROBE" /tmp/provider.log || { echo "no AUDIT_PROBE lines found" >&2; exit 1; }
echo "gate 0 probe passed"
```

In `tools/lib.sh`, add the new port to the `PORTS` list so `stop_all` waits for it to be released too, change:

```bash
PORTS="19191 19192 19193 19194 19195 19196 19291 29191 29192 29193 29194 29195 29196 29291"
```

to:

```bash
PORTS="19191 19192 19193 19194 19195 19196 19197 19291 29191 29192 29193 29194 29195 29196 29291"
```

Run: `chmod +x tools/probe-audit-events.sh && ./gradlew :connector:runtime:shadowJar && ./tools/probe-audit-events.sh`

Expected: `AUDIT_PROBE_OK`, `negotiation: FINALIZED`, at least one `AUDIT_PROBE event=...` line in `/tmp/provider.log`, and `gate 0 probe passed`.

- [ ] **Step 9: Record findings and commit**

In the task report, state plainly: which event class names actually appeared in the log (compare against the four named in spec section 2, `ContractNegotiationFinalized`, `ContractNegotiationTerminated`, `TransferProcessCompleted`, `TransferProcessTerminated`); whether more than one distinct thread name appeared across multiple probe runs (run the script twice and diff the thread names if uncertain); and confirm the audit web context was reachable without any change beyond Step 4's two properties lines. If EDC required a `data-plane-self-registration`-style extra dependency for the new web context to boot (unlikely, since `management`/`protocol`/etc. already boot the same way with no extra dependency, but confirm), add it here.

```bash
git add gradle/libs.versions.toml settings.gradle.kts connector/runtime/build.gradle.kts \
  connector/runtime/provider.properties connector/extensions/benefits-audit tools/probe-audit-events.sh tools/lib.sh
git commit -m "Add benefits-audit module skeleton and gate 0 event/web-service probe"
```

---

### Task 2: Domain records and canonical hashing

**Files:**
- Create: `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/AuditEntry.java`
- Create: `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/Epoch.java`
- Create: `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/MerkleProof.java`
- Create: `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/AuditHashing.java`
- Test: `connector/extensions/benefits-audit/src/test/java/org/benefits/audit/AuditHashingTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks (pure domain types and pure functions, no I/O).
- Produces: `AuditEntry(long seq, String epoch, String timestamp, String eventType, String actor, String datasetRef, String statedPurpose, String plainLanguage)`; `Epoch(String epochId, String merkleRoot, String prevEpochHash, String sealedAt)`; `MerkleProof(List<MerkleProof.ProofStep> steps)` with `record ProofStep(String siblingHash, boolean siblingIsRight)`; `AuditHashing.escape(String): String`, `AuditHashing.canonicalBytes(AuditEntry): byte[]`, `AuditHashing.leafHash(AuditEntry): String`, `AuditHashing.nodeHash(String, String): String`, `AuditHashing.prevEpochHash(String, String, String): String`, `AuditHashing.GENESIS_PREV_EPOCH_HASH: String`. Every later Java task in this plan depends on these exact names and signatures.

- [ ] **Step 1: Write the domain records**

Create `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/AuditEntry.java`:

```java
package org.benefits.audit;

/**
 * epoch is null until this entry has been swept into a sealed Epoch by EpochSealer
 * (Task 4). Once sealed, epoch is baked into this entry's leaf hash (AuditHashing),
 * so a store that returns a different epoch value for an already-sealed entry than
 * the one used at seal time will make that entry's inclusion proof fail to verify.
 */
public record AuditEntry(
        long seq,
        String epoch,
        String timestamp,
        String eventType,
        String actor,
        String datasetRef,
        String statedPurpose,
        String plainLanguage) {
}
```

Create `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/Epoch.java`:

```java
package org.benefits.audit;

public record Epoch(
        String epochId,
        String merkleRoot,
        String prevEpochHash,
        String sealedAt) {
}
```

Create `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/MerkleProof.java`:

```java
package org.benefits.audit;

import java.util.List;

/**
 * An ordered list of sibling hashes a verifier combines with a leaf hash, each tagged
 * with which side the sibling sits on, so the verifier never needs to know the tree's
 * shape or the leaf's index.
 */
public record MerkleProof(List<ProofStep> steps) {

    public record ProofStep(String siblingHash, boolean siblingIsRight) {
    }
}
```

- [ ] **Step 2: Write the failing hashing tests**

Create `connector/extensions/benefits-audit/src/test/java/org/benefits/audit/AuditHashingTest.java`:

```java
package org.benefits.audit;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class AuditHashingTest {

    private static final AuditEntry ENTRY = new AuditEntry(
            1L, "epoch-1", "2026-08-31T00:00:00Z", "negotiation-finalized",
            "participant-a", "asset-1", "service-improvement", "release approved");

    @Test
    void escapeOrdersBackslashBeforePipe() {
        assertThat(AuditHashing.escape("a\\b|c")).isEqualTo("a\\\\b\\|c");
    }

    @Test
    void canonicalBytesDoNotCollideAcrossTheFieldBoundary() throws Exception {
        var a = new AuditEntry(1L, "e", "t", "type", "actor|forged", "asset", "purpose", "plain");
        var b = new AuditEntry(1L, "e", "t", "type", "actor", "forged|asset", "purpose", "plain");
        assertThat(AuditHashing.canonicalBytes(a)).isNotEqualTo(AuditHashing.canonicalBytes(b));
    }

    @Test
    void leafHashIsDeterministic() {
        assertThat(AuditHashing.leafHash(ENTRY)).isEqualTo(AuditHashing.leafHash(ENTRY));
    }

    @Test
    void leafHashChangesWithAnyField() {
        var mutated = new AuditEntry(ENTRY.seq(), ENTRY.epoch(), ENTRY.timestamp(), ENTRY.eventType(),
                ENTRY.actor(), ENTRY.datasetRef(), ENTRY.statedPurpose(), "a different sentence");
        assertThat(AuditHashing.leafHash(ENTRY)).isNotEqualTo(AuditHashing.leafHash(mutated));
    }

    @Test
    void leafHashIsDomainSeparatedFromNodeHash() {
        var leaf = AuditHashing.leafHash(ENTRY);
        var node = AuditHashing.nodeHash(leaf, leaf);
        assertThat(leaf).isNotEqualTo(node);
    }

    @Test
    void nodeHashIsOrderSensitive() {
        var left = AuditHashing.leafHash(ENTRY);
        var right = AuditHashing.nodeHash(left, "0000000000000000000000000000000000000000000000000000000000000000");
        var reversed = AuditHashing.nodeHash("0000000000000000000000000000000000000000000000000000000000000000", left);
        assertThat(right).isNotEqualTo(reversed);
    }

    @Test
    void genesisPrevEpochHashIsSha256OfEmptyString() throws Exception {
        var expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest("".getBytes(StandardCharsets.UTF_8)));
        assertThat(AuditHashing.GENESIS_PREV_EPOCH_HASH).isEqualTo(expected);
    }

    @Test
    void prevEpochHashIsDeterministicAndFieldSensitive() {
        var a = AuditHashing.prevEpochHash("epoch-1", "root-a", "2026-08-31T00:00:00Z");
        var b = AuditHashing.prevEpochHash("epoch-1", "root-b", "2026-08-31T00:00:00Z");
        assertThat(a).isNotEqualTo(b);
        assertThat(a).isEqualTo(AuditHashing.prevEpochHash("epoch-1", "root-a", "2026-08-31T00:00:00Z"));
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :connector:extensions:benefits-audit:test`
Expected: FAIL, `AuditHashing` does not exist yet.

- [ ] **Step 4: Write the hashing implementation**

Create `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/AuditHashing.java`:

```java
package org.benefits.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Canonical byte encoding and domain-separated hashing shared by MerkleTree (Task 3)
 * and EpochSealer (Task 4). The escape rule is byte-identical to
 * KAnonymity.KEY_ESCAPE in benefits-privacy: replace \ with \\, then | with \|, so a
 * field value can never be crafted to collide across the separator boundary.
 */
public final class AuditHashing {

    public static final String GENESIS_PREV_EPOCH_HASH = sha256Hex(new byte[0]);

    private AuditHashing() {
    }

    public static String escape(String value) {
        return value.replace("\\", "\\\\").replace("|", "\\|");
    }

    public static byte[] canonicalBytes(AuditEntry entry) {
        var joined = String.join("|",
                escape(Long.toString(entry.seq())),
                escape(entry.epoch() == null ? "" : entry.epoch()),
                escape(entry.timestamp()),
                escape(entry.eventType()),
                escape(entry.actor()),
                escape(entry.datasetRef()),
                escape(entry.statedPurpose()),
                escape(entry.plainLanguage()));
        return joined.getBytes(StandardCharsets.UTF_8);
    }

    public static String leafHash(AuditEntry entry) {
        return domainHash((byte) 0x00, canonicalBytes(entry));
    }

    public static String nodeHash(String left, String right) {
        var leftBytes = HexFormat.of().parseHex(left);
        var rightBytes = HexFormat.of().parseHex(right);
        var combined = new byte[leftBytes.length + rightBytes.length];
        System.arraycopy(leftBytes, 0, combined, 0, leftBytes.length);
        System.arraycopy(rightBytes, 0, combined, leftBytes.length, rightBytes.length);
        return domainHash((byte) 0x01, combined);
    }

    public static String prevEpochHash(String epochId, String merkleRoot, String sealedAt) {
        var joined = String.join("|", escape(epochId), escape(merkleRoot), escape(sealedAt));
        return sha256Hex(joined.getBytes(StandardCharsets.UTF_8));
    }

    private static String domainHash(byte domain, byte[] data) {
        var combined = new byte[data.length + 1];
        combined[0] = domain;
        System.arraycopy(data, 0, combined, 1, data.length);
        return sha256Hex(combined);
    }

    private static String sha256Hex(byte[] data) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(data);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :connector:extensions:benefits-audit:test`
Expected: PASS, all 8 tests.

- [ ] **Step 6: Commit**

```bash
git add connector/extensions/benefits-audit/src/main/java/org/benefits/audit/AuditEntry.java \
  connector/extensions/benefits-audit/src/main/java/org/benefits/audit/Epoch.java \
  connector/extensions/benefits-audit/src/main/java/org/benefits/audit/MerkleProof.java \
  connector/extensions/benefits-audit/src/main/java/org/benefits/audit/AuditHashing.java \
  connector/extensions/benefits-audit/src/test/java/org/benefits/audit/AuditHashingTest.java
git commit -m "Add audit domain records and canonical hashing"
```

---

### Task 3: MerkleTree, root, proof, verify

**Files:**
- Create: `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/MerkleTree.java`
- Test: `connector/extensions/benefits-audit/src/test/java/org/benefits/audit/MerkleTreeTest.java`

**Interfaces:**
- Consumes: `AuditHashing.leafHash`, `AuditHashing.nodeHash` (Task 2).
- Produces: `MerkleTree.root(List<String> leafHashes): String`, `MerkleTree.proof(List<String> leafHashes, int index): MerkleProof`, `MerkleTree.verify(String leafHash, MerkleProof proof, String root): boolean`. `EpochSealer` (Task 4) and the conformance runner (Task 9) call `root` and `proof`; `packages/audit-verify` (Task 8) is the TypeScript equivalent of `verify` and must agree with it byte-for-byte.

- [ ] **Step 1: Write the failing tests**

Create `connector/extensions/benefits-audit/src/test/java/org/benefits/audit/MerkleTreeTest.java`:

```java
package org.benefits.audit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MerkleTreeTest {

    private static List<String> leaves(int count) {
        List<String> result = new ArrayList<>();
        for (var i = 0; i < count; i++) {
            var entry = new AuditEntry(i, "epoch-1", "2026-08-31T00:00:00Z", "type", "actor",
                    "asset-" + i, "purpose", "plain-" + i);
            result.add(AuditHashing.leafHash(entry));
        }
        return result;
    }

    @Test
    void singleLeafRootIsItsOwnLeafHash() {
        var leaves = leaves(1);
        assertThat(MerkleTree.root(leaves)).isEqualTo(leaves.get(0));
    }

    @Test
    void rootRejectsZeroLeaves() {
        assertThatThrownBy(() -> MerkleTree.root(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyIndexVerifiesAgainstTheRootAtEveryParityFromOneToTwelve() {
        for (var count = 1; count <= 12; count++) {
            var leaves = leaves(count);
            var root = MerkleTree.root(leaves);
            for (var index = 0; index < count; index++) {
                var proof = MerkleTree.proof(leaves, index);
                assertThat(MerkleTree.verify(leaves.get(index), proof, root))
                        .as("count=%d index=%d", count, index)
                        .isTrue();
            }
        }
    }

    @Test
    void aWrongLeafDoesNotVerify() {
        var leaves = leaves(5);
        var root = MerkleTree.root(leaves);
        var proof = MerkleTree.proof(leaves, 2);
        assertThat(MerkleTree.verify(leaves.get(3), proof, root)).isFalse();
    }

    @Test
    void aMutatedRootDoesNotVerify() {
        var leaves = leaves(5);
        var proof = MerkleTree.proof(leaves, 2);
        assertThat(MerkleTree.verify(leaves.get(2), proof, "0".repeat(64))).isFalse();
    }

    @Test
    void aTamperedProofStepDoesNotVerify() {
        var leaves = leaves(5);
        var root = MerkleTree.root(leaves);
        var proof = MerkleTree.proof(leaves, 2);
        var tampered = new MerkleProof(List.of(
                new MerkleProof.ProofStep("f".repeat(64), proof.steps().get(0).siblingIsRight())));
        assertThat(MerkleTree.verify(leaves.get(2), tampered, root)).isFalse();
    }

    @Test
    void proofRejectsAnOutOfRangeIndex() {
        var leaves = leaves(3);
        assertThatThrownBy(() -> MerkleTree.proof(leaves, 3)).isInstanceOf(IndexOutOfBoundsException.class);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :connector:extensions:benefits-audit:test --tests MerkleTreeTest`
Expected: FAIL, `MerkleTree` does not exist yet.

- [ ] **Step 3: Write the implementation**

Create `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/MerkleTree.java`:

```java
package org.benefits.audit;

import java.util.ArrayList;
import java.util.List;

/**
 * A pairwise Merkle tree with domain-separated leaf and internal-node hashes
 * (AuditHashing), so an internal node's hash can never be replayed as a leaf's. An
 * odd node at any level is promoted to the next level unhashed rather than
 * duplicated, avoiding the CVE-2012-2459 duplicate-leaf ambiguity. root() and
 * proof() must stay in lockstep: both walk levels using the exact same pairing and
 * promotion rule, or a proof built by one will not verify against a root built by
 * the other.
 */
public final class MerkleTree {

    private MerkleTree() {
    }

    public static String root(List<String> leafHashes) {
        if (leafHashes.isEmpty()) {
            throw new IllegalArgumentException("cannot compute a root over zero leaves");
        }
        var level = leafHashes;
        while (level.size() > 1) {
            level = nextLevel(level);
        }
        return level.get(0);
    }

    public static MerkleProof proof(List<String> leafHashes, int index) {
        if (index < 0 || index >= leafHashes.size()) {
            throw new IndexOutOfBoundsException(
                    "index " + index + " out of range for " + leafHashes.size() + " leaves");
        }
        List<MerkleProof.ProofStep> steps = new ArrayList<>();
        var level = leafHashes;
        var position = index;
        while (level.size() > 1) {
            var isLastInLevel = position == level.size() - 1;
            var isOdd = level.size() % 2 == 1;
            if (!(isOdd && isLastInLevel)) {
                var siblingOnRight = position % 2 == 0;
                var siblingIndex = siblingOnRight ? position + 1 : position - 1;
                steps.add(new MerkleProof.ProofStep(level.get(siblingIndex), siblingOnRight));
            }
            level = nextLevel(level);
            position = position / 2;
        }
        return new MerkleProof(steps);
    }

    public static boolean verify(String leafHash, MerkleProof proof, String root) {
        var current = leafHash;
        for (var step : proof.steps()) {
            current = step.siblingIsRight()
                    ? AuditHashing.nodeHash(current, step.siblingHash())
                    : AuditHashing.nodeHash(step.siblingHash(), current);
        }
        return current.equals(root);
    }

    private static List<String> nextLevel(List<String> level) {
        List<String> next = new ArrayList<>();
        var i = 0;
        while (i + 1 < level.size()) {
            next.add(AuditHashing.nodeHash(level.get(i), level.get(i + 1)));
            i += 2;
        }
        if (i < level.size()) {
            next.add(level.get(i));
        }
        return next;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :connector:extensions:benefits-audit:test --tests MerkleTreeTest`
Expected: PASS, all 7 tests. `everyIndexVerifiesAgainstTheRootAtEveryParityFromOneToTwelve` is the load-bearing one, it covers every odd/even parity a real epoch of any size could hit.

- [ ] **Step 5: Commit**

```bash
git add connector/extensions/benefits-audit/src/main/java/org/benefits/audit/MerkleTree.java \
  connector/extensions/benefits-audit/src/test/java/org/benefits/audit/MerkleTreeTest.java
git commit -m "Add domain-separated MerkleTree with root/proof/verify"
```

---

### Task 4: AuditStore interface, in-memory fake, and EpochSealer

**Files:**
- Create: `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/AuditStore.java`
- Create: `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/InMemoryAuditStore.java`
- Create: `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/EpochSealer.java`
- Test: `connector/extensions/benefits-audit/src/test/java/org/benefits/audit/EpochSealerTest.java`

**Interfaces:**
- Consumes: `AuditEntry`, `Epoch` (Task 2); `MerkleTree.root`, `MerkleTree.proof` (Task 3).
- Produces:

```java
public interface AuditStore {
    void append(AuditEntry entry);
    long nextSeq();
    List<AuditEntry> unsealedEntries();
    void sealEpoch(Epoch epoch, long firstSeq, long lastSeq);
    List<UnsealedRange> incompleteEpochs();
    List<AuditEntry> entriesFor(String participantRef);
    List<Epoch> epochChain();

    record UnsealedRange(Epoch epoch, long firstSeq, long lastSeq) {
    }
}
```

  `EpochSealer.record(AuditEntry entryWithoutSeq): AuditEntry` (assigns seq, appends, seals if threshold crossed, returns the stored entry with its assigned seq). `CouchDbAuditStore` (Task 5) implements `AuditStore` against real CouchDB. `BenefitsAuditEventListener` (Task 6) calls `EpochSealer.record`.

- [ ] **Step 1: Write the AuditStore interface**

Create `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/AuditStore.java`:

```java
package org.benefits.audit;

import java.util.List;

/**
 * Separates Merkle sealing logic from persistence, the same seam ContributionSource
 * gave benefits-privacy in phase 3: tests run the sealing logic against
 * InMemoryAuditStore, with no database, and only CouchDbAuditStore (Task 5) needs an
 * integration test.
 * <p>
 * sealEpoch and incompleteEpochs together give crash-safe resume: sealEpoch persists
 * the Epoch document (with its member seq range recorded internally, beyond the four
 * fields Epoch itself exposes) and then updates every member entry's epoch field.
 * If the process crashes between those two steps, incompleteEpochs finds the
 * already-written Epoch whose member entries are not all updated yet, and the next
 * startup resumes at the update step with the exact same seq range rather than
 * recomputing a root that a newly arrived entry could have changed.
 */
public interface AuditStore {

    void append(AuditEntry entry);

    long nextSeq();

    List<AuditEntry> unsealedEntries();

    void sealEpoch(Epoch epoch, long firstSeq, long lastSeq);

    List<UnsealedRange> incompleteEpochs();

    List<AuditEntry> entriesFor(String participantRef);

    List<Epoch> epochChain();

    record UnsealedRange(Epoch epoch, long firstSeq, long lastSeq) {
    }
}
```

- [ ] **Step 2: Write the in-memory fake**

Create `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/InMemoryAuditStore.java`:

```java
package org.benefits.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryAuditStore implements AuditStore {

    private final List<AuditEntry> entries = new ArrayList<>();
    private final List<Epoch> epochs = new ArrayList<>();
    private final AtomicLong seqCounter = new AtomicLong(0);

    @Override
    public synchronized void append(AuditEntry entry) {
        entries.add(entry);
    }

    @Override
    public long nextSeq() {
        return seqCounter.getAndIncrement();
    }

    @Override
    public synchronized List<AuditEntry> unsealedEntries() {
        return entries.stream().filter(e -> e.epoch() == null).sorted((a, b) -> Long.compare(a.seq(), b.seq())).toList();
    }

    @Override
    public synchronized void sealEpoch(Epoch epoch, long firstSeq, long lastSeq) {
        if (epochs.stream().noneMatch(e -> e.epochId().equals(epoch.epochId()))) {
            epochs.add(epoch);
        }
        for (var i = 0; i < entries.size(); i++) {
            var e = entries.get(i);
            if (e.seq() >= firstSeq && e.seq() <= lastSeq && e.epoch() == null) {
                entries.set(i, new AuditEntry(e.seq(), epoch.epochId(), e.timestamp(), e.eventType(),
                        e.actor(), e.datasetRef(), e.statedPurpose(), e.plainLanguage()));
            }
        }
    }

    @Override
    public synchronized List<UnsealedRange> incompleteEpochs() {
        List<UnsealedRange> result = new ArrayList<>();
        for (var epoch : epochs) {
            var member = entries.stream().filter(e -> epoch.epochId().equals(e.epoch())).toList();
            var stillNull = entries.stream()
                    .anyMatch(e -> e.epoch() == null && belongsToRange(e, epoch, member));
            // InMemoryAuditStore never crashes mid-seal, so this always reports none;
            // it exists so EpochSealerTest can exercise the resume path by calling
            // sealEpoch and incompleteEpochs directly without a real crash.
            if (stillNull) {
                result.add(new UnsealedRange(epoch, 0, 0));
            }
        }
        return result;
    }

    private boolean belongsToRange(AuditEntry e, Epoch epoch, List<AuditEntry> member) {
        return false;
    }

    @Override
    public synchronized List<AuditEntry> entriesFor(String participantRef) {
        return entries.stream().filter(e -> e.actor().equals(participantRef)).toList();
    }

    @Override
    public synchronized List<Epoch> epochChain() {
        return List.copyOf(epochs);
    }
}
```

- [ ] **Step 3: Write the failing EpochSealer tests**

Create `connector/extensions/benefits-audit/src/test/java/org/benefits/audit/EpochSealerTest.java`:

```java
package org.benefits.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EpochSealerTest {

    private AuditEntry entry(String actor) {
        return new AuditEntry(-1, null, "2026-08-31T00:00:00Z", "negotiation-finalized",
                actor, "asset-1", "purpose", "plain");
    }

    @Test
    void doesNotSealBeforeTheThreshold() {
        var store = new InMemoryAuditStore();
        var sealer = new EpochSealer(store, 3);
        sealer.record(entry("a"));
        sealer.record(entry("b"));
        assertThat(store.epochChain()).isEmpty();
        assertThat(store.unsealedEntries()).hasSize(2);
    }

    @Test
    void sealsExactlyAtTheThresholdWithAllMemberEntries() {
        var store = new InMemoryAuditStore();
        var sealer = new EpochSealer(store, 3);
        sealer.record(entry("a"));
        sealer.record(entry("b"));
        sealer.record(entry("c"));
        assertThat(store.epochChain()).hasSize(1);
        assertThat(store.unsealedEntries()).isEmpty();
        var sealed = store.entriesFor("a").get(0);
        assertThat(sealed.epoch()).isEqualTo(store.epochChain().get(0).epochId());
    }

    @Test
    void secondEpochChainsToTheFirstsRoot() {
        var store = new InMemoryAuditStore();
        var sealer = new EpochSealer(store, 2);
        sealer.record(entry("a"));
        sealer.record(entry("b"));
        sealer.record(entry("c"));
        sealer.record(entry("d"));
        assertThat(store.epochChain()).hasSize(2);
        var first = store.epochChain().get(0);
        var second = store.epochChain().get(1);
        assertThat(first.prevEpochHash()).isEqualTo(AuditHashing.GENESIS_PREV_EPOCH_HASH);
        assertThat(second.prevEpochHash())
                .isEqualTo(AuditHashing.prevEpochHash(first.epochId(), first.merkleRoot(), first.sealedAt()));
    }

    @Test
    void sealedEntriesVerifyAgainstTheirEpochsRoot() {
        var store = new InMemoryAuditStore();
        var sealer = new EpochSealer(store, 4);
        for (var i = 0; i < 4; i++) {
            sealer.record(entry("actor-" + i));
        }
        var epoch = store.epochChain().get(0);
        var sealedEntries = store.entriesFor("actor-0");
        var leafHashes = new java.util.ArrayList<String>();
        for (var i = 0; i < 4; i++) {
            leafHashes.add(AuditHashing.leafHash(store.entriesFor("actor-" + i).get(0)));
        }
        var proof = MerkleTree.proof(leafHashes, 0);
        assertThat(MerkleTree.verify(leafHashes.get(0), proof, epoch.merkleRoot())).isTrue();
    }
}
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `./gradlew :connector:extensions:benefits-audit:test --tests EpochSealerTest`
Expected: FAIL, `EpochSealer` does not exist yet.

- [ ] **Step 5: Write EpochSealer**

Create `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/EpochSealer.java`:

```java
package org.benefits.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The sole writer of AuditStore.append and AuditStore.sealEpoch. Whether EDC's
 * EventRouter dispatches to one subscriber on a single thread is confirmed by Task
 * 1's gate 0 probe; this lock makes correctness independent of that answer, exactly
 * as section 4.2 of the spec requires.
 */
public class EpochSealer {

    private final AuditStore store;
    private final int epochSize;
    private final ReentrantLock lock = new ReentrantLock();

    public EpochSealer(AuditStore store, int epochSize) {
        this.store = store;
        this.epochSize = epochSize;
        resumeIncompleteSeals();
    }

    public AuditEntry record(AuditEntry entryWithoutSeq) {
        lock.lock();
        try {
            var seq = store.nextSeq();
            var stamped = new AuditEntry(seq, null, entryWithoutSeq.timestamp(), entryWithoutSeq.eventType(),
                    entryWithoutSeq.actor(), entryWithoutSeq.datasetRef(), entryWithoutSeq.statedPurpose(),
                    entryWithoutSeq.plainLanguage());
            store.append(stamped);
            sealIfThresholdCrossed();
            return stamped;
        } finally {
            lock.unlock();
        }
    }

    private void sealIfThresholdCrossed() {
        var unsealed = store.unsealedEntries();
        if (unsealed.size() < epochSize) {
            return;
        }
        var toSeal = unsealed.subList(0, epochSize);
        var prior = store.epochChain();
        var prevHash = prior.isEmpty()
                ? AuditHashing.GENESIS_PREV_EPOCH_HASH
                : AuditHashing.prevEpochHash(prior.get(prior.size() - 1).epochId(),
                        prior.get(prior.size() - 1).merkleRoot(), prior.get(prior.size() - 1).sealedAt());
        var sealedAt = Instant.now().toString();
        var epochId = UUID.randomUUID().toString();

        // Leaf hashes must be computed as entries will read back AFTER sealing (epoch
        // field set to epochId), not as they sit right now (epoch field still null).
        // AuditResource later rebuilds these same leaf hashes from the persisted,
        // epoch-stamped entries to construct a proof against this root; hashing the
        // unstamped entries here would bake a different epoch value into the root
        // than AuditResource's later leaf hashes carry, and every proof would fail.
        var stampedForHashing = toSeal.stream()
                .map(e -> new AuditEntry(e.seq(), epochId, e.timestamp(), e.eventType(), e.actor(),
                        e.datasetRef(), e.statedPurpose(), e.plainLanguage()))
                .toList();
        var leafHashes = stampedForHashing.stream().map(AuditHashing::leafHash).toList();
        var root = MerkleTree.root(leafHashes);

        var epoch = new Epoch(epochId, root, prevHash, sealedAt);
        store.sealEpoch(epoch, toSeal.get(0).seq(), toSeal.get(toSeal.size() - 1).seq());
    }

    private void resumeIncompleteSeals() {
        lock.lock();
        try {
            for (var incomplete : store.incompleteEpochs()) {
                store.sealEpoch(incomplete.epoch(), incomplete.firstSeq(), incomplete.lastSeq());
            }
        } finally {
            lock.unlock();
        }
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :connector:extensions:benefits-audit:test --tests EpochSealerTest`
Expected: PASS, all 4 tests.

- [ ] **Step 7: Commit**

```bash
git add connector/extensions/benefits-audit/src/main/java/org/benefits/audit/AuditStore.java \
  connector/extensions/benefits-audit/src/main/java/org/benefits/audit/InMemoryAuditStore.java \
  connector/extensions/benefits-audit/src/main/java/org/benefits/audit/EpochSealer.java \
  connector/extensions/benefits-audit/src/test/java/org/benefits/audit/EpochSealerTest.java
git commit -m "Add AuditStore, InMemoryAuditStore, and single-writer EpochSealer"
```

---

### Task 5: CouchDbAuditStore

**Files:**
- Create: `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/CouchDbAuditStore.java`
- Test: `connector/extensions/benefits-audit/src/test/java/org/benefits/audit/CouchDbAuditStoreIntegrationTest.java`

**Interfaces:**
- Consumes: `AuditStore` (Task 4), the `java.net.http.HttpClient` / Basic-auth / Jackson `ObjectMapper` pattern `CouchDbContributionSource` established in `benefits-privacy` (phase 3).
- Produces: `CouchDbAuditStore(String baseUrl, String database, String user, String password, ObjectMapper mapper)` implementing `AuditStore`, persisting `AuditEntry` as CouchDB documents of `type: "auditEntry"` and `Epoch` as `type: "auditEpoch"` (with two CouchDB-internal-only fields, `firstSeq` and `lastSeq`, beyond the four domain fields `Epoch` itself exposes). Task 6 wires this into the running extension.

- [ ] **Step 1: Write the integration test**

Create `connector/extensions/benefits-audit/src/test/java/org/benefits/audit/CouchDbAuditStoreIntegrationTest.java`:

```java
package org.benefits.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Requires a running CouchDB at localhost:5984 with the credentials this repo's other
 * CouchDB integration tests use (see connector/runtime/provider.properties). Skips
 * itself rather than failing the build when CouchDB is not reachable, the same
 * convention benefits-privacy uses elsewhere in this repo.
 */
class CouchDbAuditStoreIntegrationTest {

    private static final String BASE_URL = "http://localhost:5984";
    private final String database = "benefits-audit-test-" + UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpClient client;
    private String auth;
    private CouchDbAuditStore store;
    private boolean created;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(couchDbReachable(), "CouchDB not reachable at " + BASE_URL);
        client = HttpClient.newHttpClient();
        auth = "Basic " + Base64.getEncoder().encodeToString("admin:benefits".getBytes());
        client.send(HttpRequest.newBuilder(URI.create(BASE_URL + "/" + database))
                .header("authorization", auth).PUT(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());
        created = true;
        store = new CouchDbAuditStore(BASE_URL, database, "admin", "benefits", mapper);
    }

    // Each test creates its own randomly-named database (above) so tests never collide
    // with each other, but a database this test creates is this test's to clean up -
    // otherwise every run of this suite leaves a permanent, never-reclaimed database on
    // whatever CouchDB instance it ran against.
    @AfterEach
    void tearDown() throws Exception {
        if (!created) {
            return;
        }
        client.send(HttpRequest.newBuilder(URI.create(BASE_URL + "/" + database))
                .header("authorization", auth).DELETE().build(), HttpResponse.BodyHandlers.discarding());
    }

    private boolean couchDbReachable() {
        try {
            HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(BASE_URL)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void appendedEntriesArePersistedAndReadableByActor() {
        var entry = new AuditEntry(1L, null, "2026-08-31T00:00:00Z", "negotiation-finalized",
                "participant-x", "asset-1", "purpose", "plain");
        store.append(entry);
        assertThat(store.unsealedEntries()).extracting(AuditEntry::actor).contains("participant-x");
        assertThat(store.entriesFor("participant-x")).hasSize(1);
    }

    @Test
    void sealEpochPersistsTheEpochAndStampsMemberEntries() {
        store.append(new AuditEntry(1L, null, "t", "type", "a", "asset", "purpose", "plain"));
        store.append(new AuditEntry(2L, null, "t", "type", "b", "asset", "purpose", "plain"));
        var epoch = new Epoch("epoch-test", "root-value", AuditHashing.GENESIS_PREV_EPOCH_HASH, "t");
        store.sealEpoch(epoch, 1L, 2L);

        assertThat(store.epochChain()).extracting(Epoch::epochId).containsExactly("epoch-test");
        assertThat(store.unsealedEntries()).isEmpty();
        assertThat(store.entriesFor("a").get(0).epoch()).isEqualTo("epoch-test");
    }

    @Test
    void nextSeqIsStrictlyIncreasing() {
        var first = store.nextSeq();
        var second = store.nextSeq();
        assertThat(second).isGreaterThan(first);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :connector:extensions:benefits-audit:test --tests CouchDbAuditStoreIntegrationTest`
Expected: FAIL, `CouchDbAuditStore` does not exist yet (or SKIPPED if CouchDB is not running locally; if so, start it, this repo's CouchDB is run via Docker Compose, check for a `docker-compose.yml` at the repo root and run `docker compose up -d couchdb` before continuing, matching how the phase 3 CouchDB dependency was brought up).

- [ ] **Step 3: Write CouchDbAuditStore**

Create `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/CouchDbAuditStore.java`:

```java
package org.benefits.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.edc.spi.EdcException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Persists AuditEntry as CouchDB documents of type "auditEntry" and Epoch as
 * "auditEpoch". The auditEpoch document additionally carries firstSeq and lastSeq,
 * beyond the four fields the public Epoch record exposes, so incompleteEpochs() can
 * find and resume a seal that crashed between writing the epoch and stamping its
 * member entries (AuditStore's class doc, and spec section 4.3).
 */
public class CouchDbAuditStore implements AuditStore {

    private static final int MAX_RESULTS = 100_000;

    private final String baseUrl;
    private final String database;
    private final String authorization;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();
    private final AtomicLong seqCounter;

    public CouchDbAuditStore(String baseUrl, String database, String user, String password, ObjectMapper mapper) {
        this.baseUrl = baseUrl;
        this.database = database;
        this.authorization = "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes());
        this.mapper = mapper;
        ensureDatabaseExists();
        this.seqCounter = new AtomicLong(loadMaxSeq() + 1);
    }

    /**
     * Unlike CouchDbContributionSource in phase 3, this store is wired directly into a
     * live connector's startup path (BenefitsAuditExtension, Task 6) and exercised by a
     * real connector process (Task 10's end-to-end probe), phase 3's CouchDB reader
     * never was. Nothing else creates the database first, so this store creates its
     * own, idempotently: 201 means it just did, 412 means another process already did
     * (both are success), anything else is a real failure.
     */
    private void ensureDatabaseExists() {
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + database))
                    .header("authorization", authorization).PUT(HttpRequest.BodyPublishers.noBody()).build();
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
    public void append(AuditEntry entry) {
        var doc = mapper.createObjectNode();
        doc.put("_id", "auditEntry-" + entry.seq());
        doc.put("type", "auditEntry");
        doc.put("seq", entry.seq());
        if (entry.epoch() != null) {
            doc.put("epoch", entry.epoch());
        }
        doc.put("timestamp", entry.timestamp());
        doc.put("eventType", entry.eventType());
        doc.put("actor", entry.actor());
        doc.put("datasetRef", entry.datasetRef());
        doc.put("statedPurpose", entry.statedPurpose());
        doc.put("plainLanguage", entry.plainLanguage());
        put("auditEntry-" + entry.seq(), doc);
    }

    @Override
    public long nextSeq() {
        return seqCounter.getAndIncrement();
    }

    @Override
    public List<AuditEntry> unsealedEntries() {
        var docs = find("{\"selector\":{\"type\":\"auditEntry\",\"epoch\":{\"$exists\":false}},\"limit\":" + MAX_RESULTS + "}");
        return parseEntries(docs).stream().sorted((a, b) -> Long.compare(a.seq(), b.seq())).toList();
    }

    @Override
    public void sealEpoch(Epoch epoch, long firstSeq, long lastSeq) {
        if (!epochDocExists(epoch.epochId())) {
            var doc = mapper.createObjectNode();
            doc.put("_id", "auditEpoch-" + epoch.epochId());
            doc.put("type", "auditEpoch");
            doc.put("epochId", epoch.epochId());
            doc.put("merkleRoot", epoch.merkleRoot());
            doc.put("prevEpochHash", epoch.prevEpochHash());
            doc.put("sealedAt", epoch.sealedAt());
            doc.put("firstSeq", firstSeq);
            doc.put("lastSeq", lastSeq);
            put("auditEpoch-" + epoch.epochId(), doc);
        }
        for (var seq = firstSeq; seq <= lastSeq; seq++) {
            stampEpoch("auditEntry-" + seq, epoch.epochId());
        }
    }

    @Override
    public List<UnsealedRange> incompleteEpochs() {
        List<UnsealedRange> result = new ArrayList<>();
        var epochDocs = find("{\"selector\":{\"type\":\"auditEpoch\"},\"limit\":" + MAX_RESULTS + "}");
        for (var doc : epochDocs.path("docs")) {
            var epoch = new Epoch(doc.get("epochId").asText(), doc.get("merkleRoot").asText(),
                    doc.get("prevEpochHash").asText(), doc.get("sealedAt").asText());
            var firstSeq = doc.get("firstSeq").asLong();
            var lastSeq = doc.get("lastSeq").asLong();
            var stillNull = false;
            for (var seq = firstSeq; seq <= lastSeq; seq++) {
                var entryDoc = getDoc("auditEntry-" + seq);
                if (entryDoc != null && !entryDoc.hasNonNull("epoch")) {
                    stillNull = true;
                    break;
                }
            }
            if (stillNull) {
                result.add(new UnsealedRange(epoch, firstSeq, lastSeq));
            }
        }
        return result;
    }

    @Override
    public List<AuditEntry> entriesFor(String participantRef) {
        var docs = find(dynamicSelector("actor", participantRef));
        return parseEntries(docs).stream().sorted((a, b) -> Long.compare(a.seq(), b.seq())).toList();
    }

    /**
     * Every selector whose value comes from outside this class (a participant-supplied
     * HTTP path parameter, an EDC-supplied asset id, a server-generated epoch id) is
     * built here, through ObjectMapper, never through string interpolation, the exact
     * defect class CouchDbContributionSource (phase 3) had to fix. A hand-rolled
     * ".replace(\"\\\"\", \"\\\\\\\"\")" escape looks safe and is not: it only escapes
     * quotes, not backslashes, so a value ending in a backslash or containing a
     * backslash-quote sequence still breaks the surrounding JSON.
     */
    private String dynamicSelector(String field, String value) {
        var selector = mapper.createObjectNode();
        var criteria = selector.putObject("selector");
        criteria.put("type", "auditEntry");
        criteria.put(field, value);
        selector.put("limit", MAX_RESULTS);
        try {
            return mapper.writeValueAsString(selector);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new EdcException("failed to serialize CouchDB selector", e);
        }
    }

    @Override
    public List<Epoch> epochChain() {
        var docs = find("{\"selector\":{\"type\":\"auditEpoch\"},\"limit\":" + MAX_RESULTS + "}");
        List<Epoch> result = new ArrayList<>();
        for (var doc : docs.path("docs")) {
            result.add(new Epoch(doc.get("epochId").asText(), doc.get("merkleRoot").asText(),
                    doc.get("prevEpochHash").asText(), doc.get("sealedAt").asText()));
        }
        result.sort((a, b) -> Long.compare(sealedAtOrdinal(a), sealedAtOrdinal(b)));
        return result;
    }

    private long sealedAtOrdinal(Epoch epoch) {
        return java.time.Instant.parse(epoch.sealedAt()).toEpochMilli();
    }

    private long loadMaxSeq() {
        var docs = find("{\"selector\":{\"type\":\"auditEntry\"},\"sort\":[{\"seq\":\"desc\"}],\"limit\":1}");
        var results = docs.path("docs");
        return results.isEmpty() ? -1 : results.get(0).get("seq").asLong();
    }

    private boolean epochDocExists(String epochId) {
        return getDoc("auditEpoch-" + epochId) != null;
    }

    private void stampEpoch(String docId, String epochId) {
        var existing = getDoc(docId);
        if (existing == null || existing.hasNonNull("epoch")) {
            return;
        }
        var doc = existing.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) doc).put("epoch", epochId);
        put(docId, doc);
    }

    private JsonNode getDoc(String id) {
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + database + "/" + id))
                    .header("authorization", authorization).GET().build();
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
            var existing = getDoc(id);
            var body = doc.deepCopy();
            if (existing != null && existing.hasNonNull("_rev")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("_rev", existing.get("_rev").asText());
            }
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + database + "/" + id))
                    .header("authorization", authorization).header("content-type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
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
                    .header("authorization", authorization).header("content-type", "application/json")
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

    private List<AuditEntry> parseEntries(JsonNode docs) {
        List<AuditEntry> result = new ArrayList<>();
        for (var doc : docs.path("docs")) {
            result.add(new AuditEntry(
                    doc.get("seq").asLong(),
                    doc.hasNonNull("epoch") ? doc.get("epoch").asText() : null,
                    doc.get("timestamp").asText(),
                    doc.get("eventType").asText(),
                    doc.get("actor").asText(),
                    doc.get("datasetRef").asText(),
                    doc.get("statedPurpose").asText(),
                    doc.get("plainLanguage").asText()));
        }
        return result;
    }
}
```

A CouchDB Mango query needs a matching index to sort by `seq`; if `loadMaxSeq`'s `"sort":[{"seq":"desc"}]` query fails with a "no matching index" error when the test runs, add a startup step to `CouchDbAuditStore`'s constructor that creates the index before it is needed:

```java
    private void ensureSeqIndex() {
        var body = "{\"index\":{\"fields\":[\"seq\"]},\"name\":\"seq-index\"}";
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + database + "/_index"))
                    .header("authorization", authorization).header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            client.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new EdcException("failed to create seq index", e);
        }
    }
```

called from the constructor before `loadMaxSeq()`. Only add this if the test actually fails on a missing index, CouchDB may pick a usable index automatically for a single-field sort.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :connector:extensions:benefits-audit:test --tests CouchDbAuditStoreIntegrationTest`
Expected: PASS, all 3 tests (or correctly SKIPPED if CouchDB genuinely cannot be started in this environment, record which happened in the task report; a skip must not be reported as a pass).

- [ ] **Step 5: Commit**

```bash
git add connector/extensions/benefits-audit/src/main/java/org/benefits/audit/CouchDbAuditStore.java \
  connector/extensions/benefits-audit/src/test/java/org/benefits/audit/CouchDbAuditStoreIntegrationTest.java
git commit -m "Add CouchDB-backed AuditStore with crash-resumable epoch sealing"
```

---

### Task 6: BenefitsAuditEventListener, real event mapping

**Files:**
- Modify: `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/BenefitsAuditExtension.java`
- Create: `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/BenefitsAuditEventListener.java`
- Create: `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/PolicyPurpose.java`
- Delete: `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/ProbeEventSubscriber.java`
- Delete: `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/ProbeAuditResource.java`
- Modify: `tools/lib.sh`
- Test: `connector/extensions/benefits-audit/src/test/java/org/benefits/audit/BenefitsAuditEventListenerTest.java`

**Interfaces:**
- Consumes: `EpochSealer.record` (Task 4); `BenefitsNamespace.PURPOSE_KEY`, `Constraints.siblingLiteral` (`benefits-policy`, phase 2); the real accessor names confirmed in Task 1's gate 0 report for `ContractNegotiationFinalized`, `ContractNegotiationTerminated`, `TransferProcessCompleted`, `TransferProcessTerminated`.
- Produces: `BenefitsAuditEventListener` registered against `EventRouter` for the four confirmed event classes (or, if Task 1 found `EventRouter.register` requires the wildcard `Event.class` form instead, adapt this task's registration to match, the mapping logic inside the listener is the same either way, only `instanceof` branches replace four separate `register` calls).

**Read this before writing `onTerminated`:** Task 1's gate 0 probe found that `ContractNegotiationTerminated` does not fire for a policy-refused negotiation, EDC 0.18.0 rejects that case synchronously, before any `ContractNegotiation` entity exists, so there is no entity for the event system to fire against. This event only ever represents a negotiation that was already admitted and later terminated (an explicit management-API termination, or a post-admission lifecycle failure). Spec section 2.2 has the full finding and the empirical evidence. The code below already reflects this in its `plainLanguage` wording and doc comment, do not revert it to language implying this event covers a policy refusal.

- [ ] **Step 1: Write PolicyPurpose**

Create `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/PolicyPurpose.java`:

```java
package org.benefits.audit;

import org.benefits.policy.BenefitsNamespace;
import org.benefits.policy.Constraints;
import org.eclipse.edc.policy.model.Policy;

/**
 * Reads the scalar benefits:purpose constraint phase 2's compiler emits (ruling R25
 * fixed this to a scalar eq, never a set, so there is exactly one value to read).
 */
final class PolicyPurpose {

    private PolicyPurpose() {
    }

    static String extract(Policy policy) {
        if (policy == null || policy.getPermissions().isEmpty()) {
            return "unknown";
        }
        var purpose = Constraints.siblingLiteral(policy.getPermissions().get(0), BenefitsNamespace.PURPOSE_KEY);
        return purpose == null ? "unknown" : purpose;
    }
}
```

- [ ] **Step 2: Write the failing listener test**

Create `connector/extensions/benefits-audit/src/test/java/org/benefits/audit/BenefitsAuditEventListenerTest.java`. This test exercises the mapping logic directly against constructed event objects rather than a running EDC instance, the running-instance path is Task 10's end-to-end gate.

```java
package org.benefits.audit;

import org.eclipse.edc.connector.controlplane.contract.spi.event.contractnegotiation.ContractNegotiationFinalized;
import org.eclipse.edc.connector.controlplane.contract.spi.event.contractnegotiation.ContractNegotiationTerminated;
import org.eclipse.edc.connector.controlplane.contract.spi.types.agreement.ContractAgreement;
import org.eclipse.edc.connector.controlplane.contract.spi.types.offer.ContractOffer;
import org.eclipse.edc.connector.controlplane.transfer.spi.event.TransferProcessCompleted;
import org.eclipse.edc.policy.model.Action;
import org.eclipse.edc.policy.model.AtomicConstraint;
import org.eclipse.edc.policy.model.LiteralExpression;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.policy.model.Permission;
import org.eclipse.edc.policy.model.Policy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BenefitsAuditEventListenerTest {

    private Policy policyWithPurpose(String purpose) {
        var constraint = AtomicConstraint.Builder.newInstance()
                .leftExpression(new LiteralExpression(org.benefits.policy.BenefitsNamespace.PURPOSE_KEY))
                .operator(Operator.EQ)
                .rightExpression(new LiteralExpression(purpose))
                .build();
        var permission = Permission.Builder.newInstance()
                .action(Action.Builder.newInstance().type("use").build())
                .constraint(constraint)
                .build();
        return Policy.Builder.newInstance().permission(permission).build();
    }

    @Test
    void finalizedNegotiationProducesAnEntryWithActorAssetAndPurpose() {
        var agreement = ContractAgreement.Builder.newInstance()
                .id("agreement-1").providerId("provider").consumerId("consumer")
                .assetId("asset-1").policy(policyWithPurpose("service-improvement"))
                .build();
        var event = ContractNegotiationFinalized.Builder.newInstance()
                .contractNegotiationId("neg-1").counterPartyId("participant-a")
                .counterPartyAddress("http://x").protocol("dataspace-protocol-http:2025-1")
                .contractAgreement(agreement).build();

        var store = new InMemoryAuditStore();
        var sealer = new EpochSealer(store, 100);
        var listener = new BenefitsAuditEventListener(sealer);
        listener.onFinalized(event);

        var recorded = store.unsealedEntries().get(0);
        assertThat(recorded.actor()).isEqualTo("participant-a");
        assertThat(recorded.datasetRef()).isEqualTo("asset-1");
        assertThat(recorded.statedPurpose()).isEqualTo("service-improvement");
        assertThat(recorded.eventType()).isEqualTo("negotiation-finalized");
    }

    @Test
    void terminatedNegotiationReadsAssetAndPurposeFromTheLastOffer() {
        var offer = ContractOffer.Builder.newInstance().id("offer-1").assetId("asset-2")
                .policy(policyWithPurpose("advocacy")).build();
        var event = ContractNegotiationTerminated.Builder.newInstance()
                .contractNegotiationId("neg-2").counterPartyId("participant-b")
                .counterPartyAddress("http://x").protocol("dataspace-protocol-http:2025-1")
                .contractOffers(java.util.List.of(offer)).build();

        var store = new InMemoryAuditStore();
        var sealer = new EpochSealer(store, 100);
        var listener = new BenefitsAuditEventListener(sealer);
        listener.onTerminated(event);

        var recorded = store.unsealedEntries().get(0);
        assertThat(recorded.actor()).isEqualTo("participant-b");
        assertThat(recorded.datasetRef()).isEqualTo("asset-2");
        assertThat(recorded.statedPurpose()).isEqualTo("advocacy");
        assertThat(recorded.eventType()).isEqualTo("negotiation-terminated");
    }

    @Test
    void transferCompletedCorrelatesActorAndPurposeByContractId() {
        var store = new InMemoryAuditStore();
        var sealer = new EpochSealer(store, 100);
        var listener = new BenefitsAuditEventListener(sealer);

        var agreement = ContractAgreement.Builder.newInstance()
                .id("agreement-3").providerId("provider").consumerId("consumer")
                .assetId("asset-3").policy(policyWithPurpose("statistics"))
                .build();
        var negotiationEvent = ContractNegotiationFinalized.Builder.newInstance()
                .contractNegotiationId("neg-3").counterPartyId("participant-c")
                .counterPartyAddress("http://x").protocol("dataspace-protocol-http:2025-1")
                .contractAgreement(agreement).build();
        listener.onFinalized(negotiationEvent);

        var transferEvent = TransferProcessCompleted.Builder.newInstance()
                .transferProcessId("tp-1").assetId("asset-3").type("PUSH").contractId("agreement-3")
                .build();
        listener.onTransferCompleted(transferEvent);

        var entries = store.entriesFor("participant-c");
        assertThat(entries).hasSize(2);
        var transferEntry = entries.stream().filter(e -> "transfer-completed".equals(e.eventType())).findFirst().orElseThrow();
        assertThat(transferEntry.statedPurpose()).isEqualTo("statistics");
        assertThat(transferEntry.datasetRef()).isEqualTo("asset-3");
    }

    @Test
    void transferCorrelatesByItsOwnAgreementEvenWhenTheSameAssetWasNegotiatedTwice() {
        var store = new InMemoryAuditStore();
        var sealer = new EpochSealer(store, 100);
        var listener = new BenefitsAuditEventListener(sealer);

        var firstAgreement = ContractAgreement.Builder.newInstance()
                .id("agreement-first").providerId("provider").consumerId("consumer")
                .assetId("shared-asset").policy(policyWithPurpose("advocacy"))
                .build();
        listener.onFinalized(ContractNegotiationFinalized.Builder.newInstance()
                .contractNegotiationId("neg-first").counterPartyId("participant-first")
                .counterPartyAddress("http://x").protocol("dataspace-protocol-http:2025-1")
                .contractAgreement(firstAgreement).build());

        var secondAgreement = ContractAgreement.Builder.newInstance()
                .id("agreement-second").providerId("provider").consumerId("consumer")
                .assetId("shared-asset").policy(policyWithPurpose("statistics"))
                .build();
        listener.onFinalized(ContractNegotiationFinalized.Builder.newInstance()
                .contractNegotiationId("neg-second").counterPartyId("participant-second")
                .counterPartyAddress("http://x").protocol("dataspace-protocol-http:2025-1")
                .contractAgreement(secondAgreement).build());

        // The transfer belongs to the FIRST negotiation's agreement, even though the
        // SECOND negotiation (for the same asset) is more recent, proving
        // correlation is keyed by agreement id, not "most recent entry for this
        // asset" (the bug Task 6's reviewer found in the original design).
        var transferEvent = TransferProcessCompleted.Builder.newInstance()
                .transferProcessId("tp-shared").assetId("shared-asset").type("PUSH").contractId("agreement-first")
                .build();
        listener.onTransferCompleted(transferEvent);

        var transferEntry = store.entriesFor("participant-first").stream()
                .filter(e -> "transfer-completed".equals(e.eventType())).findFirst().orElseThrow();
        assertThat(transferEntry.actor()).isEqualTo("participant-first");
        assertThat(transferEntry.statedPurpose()).isEqualTo("advocacy");
        assertThat(store.entriesFor("participant-second"))
                .noneMatch(e -> "transfer-completed".equals(e.eventType()));
    }
}
```

If any builder method name above does not match the real EDC 0.18.0 API (for example `ContractAgreement.Builder` may name its provider/consumer setters differently, or `Permission.Builder` may not take `.constraint(...)` directly), correct the test to the real builder API, cross-check against how `StewardQuorumFunctionTest.java` or `ConsentConstraintFunctionsTest.java` in `benefits-policy` already construct `Permission`/`AtomicConstraint` objects for their own tests, since those tests already compile against this exact EDC version.

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :connector:extensions:benefits-audit:test --tests BenefitsAuditEventListenerTest`
Expected: FAIL, `BenefitsAuditEventListener` does not exist yet.

- [ ] **Step 4: Write BenefitsAuditEventListener**

Create `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/BenefitsAuditEventListener.java`:

```java
package org.benefits.audit;

import org.eclipse.edc.connector.controlplane.contract.spi.event.contractnegotiation.ContractNegotiationFinalized;
import org.eclipse.edc.connector.controlplane.contract.spi.event.contractnegotiation.ContractNegotiationTerminated;
import org.eclipse.edc.connector.controlplane.transfer.spi.event.TransferProcessCompleted;
import org.eclipse.edc.connector.controlplane.transfer.spi.event.TransferProcessTerminated;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps four EDC event types into AuditEntry and hands each to EpochSealer.
 * TransferProcessEvent carries no counter-party or policy accessor (confirmed by
 * inspecting the compiled class directly), so a transfer entry correlates its actor
 * and statedPurpose from the negotiation that produced its agreement, keyed by
 * agreement id (spec section 6), correlating by asset id instead is ambiguous
 * whenever the same asset is negotiated more than once, which an earlier version of
 * this class did and a task reviewer caught. The cache is in-memory only and does
 * not survive a restart; a transfer completing after a restart between its
 * negotiation and the transfer itself falls back to "unknown", the same honest
 * fallback used for any other cache miss.
 * <p>
 * ContractNegotiationTerminated does NOT cover a policy-refused negotiation (spec
 * section 2.2): EDC 0.18.0 rejects an offer that fails ODRL constraint evaluation
 * synchronously, before any ContractNegotiation entity exists, so no event of any
 * kind fires for that case. This handler only ever sees a negotiation that was
 * already admitted into the provider's state machine and later terminated (an
 * explicit management-API termination, or a post-admission lifecycle failure) -
 * never a k-anonymity, epsilon-budget, steward-quorum, purpose, or recipient-class
 * refusal at initial offer validation. Do not describe this entry's plainLanguage as
 * a "refusal" in a way that implies it covers that case.
 */
public class BenefitsAuditEventListener {

    private final EpochSealer sealer;
    private final Map<String, NegotiationInfo> agreementCache = new ConcurrentHashMap<>();

    public BenefitsAuditEventListener(EpochSealer sealer) {
        this.sealer = sealer;
    }

    public void onFinalized(ContractNegotiationFinalized event) {
        var agreement = event.getContractAgreement();
        var purpose = PolicyPurpose.extract(agreement.getPolicy());
        agreementCache.put(agreement.getId(), new NegotiationInfo(event.getCounterPartyId(), purpose));
        sealer.record(new AuditEntry(-1, null, Instant.now().toString(), "negotiation-finalized",
                event.getCounterPartyId(), agreement.getAssetId(), purpose,
                "release request approved for asset " + agreement.getAssetId()));
    }

    public void onTerminated(ContractNegotiationTerminated event) {
        var offer = event.getContractOffers().isEmpty() ? null : event.getLastContractOffer();
        var assetId = offer == null ? "unknown" : offer.getAssetId();
        var purpose = offer == null ? "unknown" : PolicyPurpose.extract(offer.getPolicy());
        sealer.record(new AuditEntry(-1, null, Instant.now().toString(), "negotiation-terminated",
                event.getCounterPartyId(), assetId, purpose,
                "negotiation for asset " + assetId + " was admitted and later terminated"));
    }

    public void onTransferCompleted(TransferProcessCompleted event) {
        recordTransfer(event.getContractId(), event.getAssetId(), event.getParticipantContextId(),
                "transfer-completed", "anonymised data delivered for asset " + event.getAssetId());
    }

    public void onTransferTerminated(TransferProcessTerminated event) {
        recordTransfer(event.getContractId(), event.getAssetId(), event.getParticipantContextId(),
                "transfer-terminated", "transfer failed for asset " + event.getAssetId() + ": " + event.getReason());
    }

    private void recordTransfer(String contractId, String assetId, String fallbackActor, String eventType,
            String plainLanguage) {
        var cached = agreementCache.get(contractId);
        var actor = cached != null ? cached.actor() : fallbackActor;
        var purpose = cached != null ? cached.statedPurpose()
                : "unknown (no cached negotiation info for contract " + contractId + ")";
        sealer.record(new AuditEntry(-1, null, Instant.now().toString(), eventType, actor, assetId, purpose,
                plainLanguage));
    }

    private record NegotiationInfo(String actor, String statedPurpose) {
    }
}
```

Correlation is entirely internal to this class, an in-memory `Map` populated in `onFinalized` and read in `recordTransfer`, keyed by the agreement id both `ContractAgreement.getId()` (negotiation side) and `TransferProcessEvent.getContractId()` (transfer side) share. It does not touch `EpochSealer` or `AuditStore` at all; no new method on either is needed for this task.

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :connector:extensions:benefits-audit:test --tests BenefitsAuditEventListenerTest`
Expected: PASS, all 4 tests (including `transferCorrelatesByItsOwnAgreementEvenWhenTheSameAssetWasNegotiatedTwice`, the regression test for the agreement-id correlation fix).

- [ ] **Step 6: Wire the real listener into the extension and delete the probes**

Delete `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/ProbeEventSubscriber.java` and `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/ProbeAuditResource.java`, their job (proving `EventRouter` and `WebService` both work here) is done.

Replace `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/BenefitsAuditExtension.java` with:

```java
package org.benefits.audit;

import org.eclipse.edc.connector.controlplane.contract.spi.event.contractnegotiation.ContractNegotiationFinalized;
import org.eclipse.edc.connector.controlplane.contract.spi.event.contractnegotiation.ContractNegotiationTerminated;
import org.eclipse.edc.connector.controlplane.transfer.spi.event.TransferProcessCompleted;
import org.eclipse.edc.connector.controlplane.transfer.spi.event.TransferProcessTerminated;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.spi.event.Event;
import org.eclipse.edc.spi.event.EventEnvelope;
import org.eclipse.edc.spi.event.EventRouter;
import org.eclipse.edc.spi.event.EventSubscriber;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.web.spi.WebService;
import org.eclipse.edc.web.spi.configuration.PortMapping;
import org.eclipse.edc.web.spi.configuration.PortMappingRegistry;

import com.fasterxml.jackson.databind.ObjectMapper;

public class BenefitsAuditExtension implements ServiceExtension {

    @Inject
    private EventRouter eventRouter;
    @Inject
    private WebService webService;
    @Inject
    private PortMappingRegistry portMappingRegistry;

    @Override
    public String name() {
        return "BENEFITS audit chain";
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        var monitor = context.getMonitor();
        if (!Boolean.parseBoolean(context.getSetting("benefits.audit.enabled", "false"))) {
            monitor.info("BENEFITS audit chain disabled (benefits.audit.enabled not set), skipping");
            return;
        }
        var epochSize = Integer.parseInt(context.getSetting("benefits.audit.epochSize", "50"));
        var store = buildStore(context);
        var sealer = new EpochSealer(store, epochSize);
        var listener = new BenefitsAuditEventListener(sealer);

        eventRouter.register(ContractNegotiationFinalized.class, envelopeSubscriber(listener::onFinalized));
        eventRouter.register(ContractNegotiationTerminated.class, envelopeSubscriber(listener::onTerminated));
        eventRouter.register(TransferProcessCompleted.class, envelopeSubscriber(listener::onTransferCompleted));
        eventRouter.register(TransferProcessTerminated.class, envelopeSubscriber(listener::onTransferTerminated));

        // Task 1's gate 0 probe found this is required: JettyExtension only
        // auto-registers the default (unnamed) web.http.port context. Every other
        // named context (management, protocol, ...) has its own configuration
        // extension elsewhere in the EDC BOM that does this same call before
        // registerResource; "audit" is a brand-new context name with no such
        // extension, so this extension must register its own PortMapping or the
        // provider fails to boot with "No PortMapping for contextName 'audit' found".
        var port = context.getSetting("web.http.audit.port", 19197);
        var path = context.getSetting("web.http.audit.path", "/audit");
        portMappingRegistry.register(new PortMapping("audit", port, path));
        webService.registerResource("audit", new AuditResource(store));
        monitor.info("BENEFITS audit chain registered, epochSize=" + epochSize);
    }

    private AuditStore buildStore(ServiceExtensionContext context) {
        var url = context.getSetting("benefits.couchdb.url", "http://localhost:5984");
        var database = context.getSetting("benefits.couchdb.database", "benefits") + "-audit";
        var user = context.getSetting("benefits.couchdb.user", "admin");
        var password = context.getSetting("benefits.couchdb.password", "");
        return new CouchDbAuditStore(url, database, user, password, new ObjectMapper());
    }

    private <T extends Event> EventSubscriber envelopeSubscriber(java.util.function.Consumer<T> handler) {
        // EventSubscriber.on is confirmed (Task 1's gate 0 report) to be a generic
        // method, <E extends Event> void on(EventEnvelope<E>), not a method taking
        // a wildcarded parameter. A lambda expression cannot implement an interface
        // method that declares its own type parameter (there is no lambda syntax for
        // <E>), so this must be an anonymous class, not `envelope -> ...`.
        return new EventSubscriber() {
            @Override
            @SuppressWarnings("unchecked")
            public <E extends Event> void on(EventEnvelope<E> envelope) {
                handler.accept((T) envelope.getPayload());
            }
        };
    }
}
```

`EventRouter.register(Class<E>, EventSubscriber)` was verified directly against `EventRouterImpl`'s bytecode (`~/.gradle/caches/.../org.eclipse.edc/runtime-core/0.18.0/`) to filter delivery by `registeredClass.isAssignableFrom(envelope.getPayload().getClass())` before invoking each subscriber, so each of the four `register` calls above only ever delivers its own event type to its own `envelopeSubscriber`, and the unchecked cast inside it is sound. This was checked because Task 1's report only confirmed the wildcard `register(Event.class, ...)` form (which matches everything); registering four subscribers under four different specific classes and trusting each to receive only its own type was not directly probed, so it was verified here instead of assumed.

The `benefits-audit` database name is `<benefits.couchdb.database>-audit` (default `benefits-audit`), kept separate from the `benefits` database phase 3's `CouchDbContributionSource` reads contribution records from, since audit documents (`auditEntry`, `auditEpoch`) are a different concern than contribution records (`contribution`) and mixing them would make every Mango query in both stores need an extra `type` filter to avoid cross-contamination, they already have one, so this is a defense-in-depth separation, not a strict necessity, but it costs nothing.

If `AuditResource` (referenced above) does not exist yet, that is expected, it is Task 7. This task will not compile until Task 7 is complete; that is acceptable within a single task's own commit only if Task 7 is the very next task in this plan, which it is. If your workflow requires every task to compile and test green in isolation, write a placeholder `AuditResource` in this task instead:

```java
package org.benefits.audit;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/")
class AuditResource {
    AuditResource(AuditStore store) {
    }
}
```

and let Task 7 replace it with the real implementation.

- [ ] **Step 7: Make the shared connector-starting tooling ensure CouchDB is running**

From this task onward, the provider connector cannot boot at all without CouchDB reachable, `CouchDbAuditStore`'s constructor (Task 5) connects immediately. Phases 0-3's own scripts (`tools/run-case.sh`, `probe-transfer.sh`, `probe-aggregate-transfer.sh`, and this phase's own probes) all start connectors through the single shared `start_connector` function in `tools/lib.sh`, so fixing it there once, rather than in every script, is what keeps this from silently breaking every existing test in the repo the next time any of them runs on this branch.

In `tools/lib.sh`, add this function above `start_connector`:

```bash
# From benefits-audit onward, a booting connector needs CouchDB reachable
# (CouchDbAuditStore connects at construction time). Every script that starts
# a connector goes through start_connector below, so this lives here once
# rather than being repeated in every script.
ensure_couchdb() {
  if curl -sf http://localhost:5984 >/dev/null 2>&1; then
    return 0
  fi
  (cd "$ROOT" && docker compose -f deploy/docker-compose.yml up -d couchdb) >/dev/null
  for _ in $(seq 1 30); do
    curl -sf http://localhost:5984 >/dev/null 2>&1 && return 0
    sleep 1
  done
  echo "CouchDB did not become reachable at localhost:5984" >&2
  return 1
}
```

Then change the first line of `start_connector`'s body to call it:

```bash
start_connector() {
  ensure_couchdb || return 1
  local name=$1 config=$2 mgmt_port=$3
```

This runs for both the provider and the consumer even though only the provider actually uses CouchDB (Step 4's `benefits.audit.enabled` flag keeps the consumer's copy of the extension a no-op), a single reachability check is cheap, and keeping `start_connector` unconditional is simpler than threading "does this specific properties file need CouchDB" through every caller.

Run: `./tools/probe-audit-events.sh` again (Task 1's gate 0 probe) to confirm `ensure_couchdb` does not break it, it should still pass exactly as it did in Task 1, since that probe's own `BenefitsAuditExtension` copy has been replaced by this task's real one, which still honors `benefits.audit.enabled` on the provider.

- [ ] **Step 8: Compile and run the full module test suite**

Run: `./gradlew :connector:extensions:benefits-audit:test`
Expected: PASS (compilation succeeds once the placeholder or real `AuditResource` from the note above exists).

- [ ] **Step 9: Commit**

```bash
git add connector/extensions/benefits-audit/src/main/java/org/benefits/audit/BenefitsAuditEventListener.java \
  connector/extensions/benefits-audit/src/main/java/org/benefits/audit/PolicyPurpose.java \
  connector/extensions/benefits-audit/src/main/java/org/benefits/audit/BenefitsAuditExtension.java \
  connector/runtime/provider.properties tools/lib.sh \
  connector/extensions/benefits-audit/src/test/java/org/benefits/audit/BenefitsAuditEventListenerTest.java
git rm connector/extensions/benefits-audit/src/main/java/org/benefits/audit/ProbeEventSubscriber.java \
  connector/extensions/benefits-audit/src/main/java/org/benefits/audit/ProbeAuditResource.java
git commit -m "Wire real event-to-entry mapping into the audit extension, remove gate 0 probes"
```

---

### Task 7: AuditResource, the participant proof endpoint

**Files:**
- Create: `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/AuditResource.java` (replacing Task 6's placeholder, if one was used)
- Test: `connector/extensions/benefits-audit/src/test/java/org/benefits/audit/AuditResourceTest.java`

**Interfaces:**
- Consumes: `AuditStore.entriesFor`, `AuditStore.epochChain` (Task 4/5); `MerkleTree.proof` (Task 3).
- Produces: `GET /audit/{participantRef}` returning `{ entries, proofs, epochs }` as specified in spec section 7. Task 10's end-to-end probe and the participant-side TypeScript verifier (Task 8) both consume this exact response shape.

- [ ] **Step 1: Write the failing resource test**

Create `connector/extensions/benefits-audit/src/test/java/org/benefits/audit/AuditResourceTest.java`:

```java
package org.benefits.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditResourceTest {

    @Test
    void returnsOnlyEntriesForTheRequestedParticipantWithAProofPerEntryAndTheFullEpochChain() {
        var store = new InMemoryAuditStore();
        var sealer = new EpochSealer(store, 2);
        sealer.record(new AuditEntry(-1, null, "t1", "negotiation-finalized", "alice", "asset-1", "purpose", "plain-a"));
        sealer.record(new AuditEntry(-1, null, "t2", "negotiation-finalized", "bob", "asset-2", "purpose", "plain-b"));
        sealer.record(new AuditEntry(-1, null, "t3", "negotiation-finalized", "alice", "asset-3", "purpose", "plain-c"));

        var resource = new AuditResource(store);
        var response = resource.forParticipant("alice");

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

        var resource = new AuditResource(store);
        var response = resource.forParticipant("alice");

        assertThat(response.entries()).hasSize(1);
        assertThat(response.proofs()).isEmpty();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :connector:extensions:benefits-audit:test --tests AuditResourceTest`
Expected: FAIL, `AuditResource.forParticipant` does not exist yet (or the placeholder from Task 6 does not compile against this test).

- [ ] **Step 3: Write AuditResource**

Create (or replace) `connector/extensions/benefits-audit/src/main/java/org/benefits/audit/AuditResource.java`:

```java
package org.benefits.audit;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A participant is not a dataspace-protocol consumer, so this is a plain read-only
 * resource registered directly against EDC's WebService, not a data plane transfer
 * (spec section 7). Returning only entries whose actor equals the requested
 * participantRef is the whole privacy boundary this endpoint provides: a participant
 * sees their own entries and the full public root chain, never anyone else's entries.
 */
@Path("/{participantRef}")
public class AuditResource {

    private final AuditStore store;

    public AuditResource(AuditStore store) {
        this.store = store;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public AuditResponse forParticipant(@PathParam("participantRef") String participantRef) {
        var entries = store.entriesFor(participantRef);
        var epochs = store.epochChain();

        Map<String, List<String>> leavesByEpoch = new HashMap<>();
        for (var epoch : epochs) {
            // Every sealed entry's leaf position within its own epoch is needed to build
            // that entry's proof; recompute each epoch's full leaf list from every entry
            // sealed under it, in seq order, matching exactly how EpochSealer built it.
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

        return new AuditResponse(entries, proofs, epochs);
    }

    private List<String> leafHashesFor(String epochId) {
        return store.entriesFor(epochId, true).stream().map(AuditHashing::leafHash).toList();
    }

    private int indexOfSeq(String epochId, long seq) {
        var members = store.entriesFor(epochId, true);
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

This calls `store.entriesFor(epochId, true)`, an overload that does not exist yet, `AuditStore.entriesFor(String)` filters by `actor`, but rebuilding an epoch's leaf list needs every entry whose `epoch` field equals a given epoch id, in seq order, which is a different filter. Rather than overload a method whose single-argument form already means something else, add a differently named method instead:

In `AuditStore.java`, add to the interface:

```java
    List<AuditEntry> entriesInEpoch(String epochId);
```

In `InMemoryAuditStore.java`, add:

```java
    @Override
    public synchronized List<AuditEntry> entriesInEpoch(String epochId) {
        return entries.stream().filter(e -> epochId.equals(e.epoch()))
                .sorted((a, b) -> Long.compare(a.seq(), b.seq())).toList();
    }
```

In `CouchDbAuditStore.java`, add (reuses the same `dynamicSelector` helper `entriesFor` already uses, build the selector through it, never through string concatenation, since a hand-rolled escape was a real Critical finding on this exact class of method):

```java
    @Override
    public List<AuditEntry> entriesInEpoch(String epochId) {
        var docs = find(dynamicSelector("epoch", epochId));
        return parseEntries(docs).stream().sorted((a, b) -> Long.compare(a.seq(), b.seq())).toList();
    }
```

Then in `AuditResource.java`, replace the two calls to `store.entriesFor(epochId, true)` (in `leafHashesFor` and `indexOfSeq`) with `store.entriesInEpoch(epochId)`.

Add one integration test to `CouchDbAuditStoreIntegrationTest.java` (Task 5), alongside its existing `sealEpochPersistsTheEpochAndStampsMemberEntries` test, `entriesInEpoch` is new production code on the path Task 6 already wires into a live connector, and `entriesFor`'s equivalent CouchDB behavior already has integration coverage in that same file, so this method should too rather than being verified only against `InMemoryAuditStore`:

```java
    @Test
    void entriesInEpochReturnsSealedMembersInSeqOrder() {
        store.append(new AuditEntry(1L, null, "t", "type", "b", "asset", "purpose", "plain"));
        store.append(new AuditEntry(2L, null, "t", "type", "a", "asset", "purpose", "plain"));
        var epoch = new Epoch("epoch-inEpoch-test", "root-value", AuditHashing.GENESIS_PREV_EPOCH_HASH, "t");
        store.sealEpoch(epoch, 1L, 2L);

        var members = store.entriesInEpoch("epoch-inEpoch-test");
        assertThat(members).extracting(AuditEntry::seq).containsExactly(1L, 2L);
        assertThat(members).allMatch(e -> "epoch-inEpoch-test".equals(e.epoch()));
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :connector:extensions:benefits-audit:test --tests AuditResourceTest`
Expected: PASS, both tests.

- [ ] **Step 5: Run the full module suite**

Run: `./gradlew :connector:extensions:benefits-audit:test`
Expected: PASS, every test in the module (this also re-runs Tasks 2-6's tests, confirming nothing this task touched broke them, `AuditStore`'s interface grew by one method twice, in this task and Task 6, and both implementations must still compile).

- [ ] **Step 6: Commit**

```bash
git add connector/extensions/benefits-audit/src/main/java/org/benefits/audit/AuditResource.java \
  connector/extensions/benefits-audit/src/main/java/org/benefits/audit/AuditStore.java \
  connector/extensions/benefits-audit/src/main/java/org/benefits/audit/InMemoryAuditStore.java \
  connector/extensions/benefits-audit/src/main/java/org/benefits/audit/CouchDbAuditStore.java \
  connector/extensions/benefits-audit/src/test/java/org/benefits/audit/AuditResourceTest.java
git commit -m "Add the participant proof endpoint, GET /audit/{participantRef}"
```

---

### Task 8: packages/audit-verify, the TypeScript participant verifier

**Files:**
- Create: `packages/audit-verify/package.json`
- Create: `packages/audit-verify/tsconfig.json`
- Create: `packages/audit-verify/src/hashing.ts`
- Create: `packages/audit-verify/src/merkle.ts`
- Create: `packages/audit-verify/src/chain.ts`
- Create: `packages/audit-verify/src/index.ts`
- Test: `packages/audit-verify/test/hashing.test.ts`
- Test: `packages/audit-verify/test/merkle.test.ts`
- Test: `packages/audit-verify/test/chain.test.ts`

**Interfaces:**
- Consumes: nothing from earlier tasks, this package has no dependency on any Java module and only agrees with it by the shared hashing rule from spec sections 3.2/3.3, verified independently by Task 9's cross-language conformance vectors.
- Produces: `escape`, `canonicalBytes`, `leafHash`, `nodeHash`, `prevEpochHash`, `GENESIS_PREV_EPOCH_HASH` (hashing.ts); `verifyInclusion` (merkle.ts); `verifyChain` (chain.ts). Task 9's TypeScript conformance runner imports `leafHash` directly by name.

- [ ] **Step 1: Scaffold the package**

Create `packages/audit-verify/package.json`:

```json
{
  "name": "@benefits/audit-verify",
  "version": "0.1.0",
  "type": "module",
  "main": "dist/index.js",
  "types": "dist/index.d.ts",
  "scripts": {
    "build": "tsc -p tsconfig.json",
    "test": "vitest run"
  }
}
```

Create `packages/audit-verify/tsconfig.json`:

```json
{
  "extends": "../../tsconfig.base.json",
  "compilerOptions": { "outDir": "dist", "rootDir": "src" },
  "include": ["src"]
}
```

- [ ] **Step 2: Write the failing hashing tests**

Create `packages/audit-verify/test/hashing.test.ts`:

```typescript
import { describe, expect, it } from 'vitest';
import { escape, canonicalBytes, leafHash, nodeHash, prevEpochHash, GENESIS_PREV_EPOCH_HASH } from '../src/hashing.js';
import type { AuditEntry } from '../src/hashing.js';

const entry: AuditEntry = {
  seq: 1, epoch: 'epoch-1', timestamp: '2026-08-31T00:00:00Z', eventType: 'negotiation-finalized',
  actor: 'participant-a', datasetRef: 'asset-1', statedPurpose: 'service-improvement', plainLanguage: 'release approved',
};

describe('escape', () => {
  it('orders backslash before pipe', () => {
    expect(escape('a\\b|c')).toBe('a\\\\b\\|c');
  });
});

describe('canonicalBytes', () => {
  it('does not collide across the field boundary', () => {
    const a: AuditEntry = { ...entry, actor: 'actor|forged', datasetRef: 'asset' };
    const b: AuditEntry = { ...entry, actor: 'actor', datasetRef: 'forged|asset' };
    expect(canonicalBytes(a)).not.toEqual(canonicalBytes(b));
  });
});

describe('leafHash', () => {
  it('is deterministic', async () => {
    expect(await leafHash(entry)).toBe(await leafHash(entry));
  });

  it('changes with any field', async () => {
    const mutated: AuditEntry = { ...entry, plainLanguage: 'a different sentence' };
    expect(await leafHash(entry)).not.toBe(await leafHash(mutated));
  });

  it('is domain separated from nodeHash', async () => {
    const leaf = await leafHash(entry);
    const node = await nodeHash(leaf, leaf);
    expect(leaf).not.toBe(node);
  });
});

describe('nodeHash', () => {
  it('is order sensitive', async () => {
    const leaf = await leafHash(entry);
    const zero = '0'.repeat(64);
    expect(await nodeHash(leaf, zero)).not.toBe(await nodeHash(zero, leaf));
  });
});

describe('GENESIS_PREV_EPOCH_HASH', () => {
  it('is sha256 of the empty string', async () => {
    expect(await GENESIS_PREV_EPOCH_HASH).toHaveLength(64);
  });
});

describe('prevEpochHash', () => {
  it('is deterministic and field sensitive', async () => {
    const a = await prevEpochHash('epoch-1', 'root-a', '2026-08-31T00:00:00Z');
    const b = await prevEpochHash('epoch-1', 'root-b', '2026-08-31T00:00:00Z');
    expect(a).not.toBe(b);
    expect(a).toBe(await prevEpochHash('epoch-1', 'root-a', '2026-08-31T00:00:00Z'));
  });
});
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cd packages/audit-verify && npm install && npx vitest run test/hashing.test.ts`
Expected: FAIL, `src/hashing.ts` does not exist yet.

- [ ] **Step 4: Write hashing.ts**

Create `packages/audit-verify/src/hashing.ts`:

```typescript
// Byte-identical to AuditHashing.java (connector/extensions/benefits-audit) by
// construction, and proven so by the shared conformance vectors under
// conformance/audit-vectors/. The escape rule matches KAnonymity.KEY_ESCAPE in
// benefits-privacy: replace \ with \\, then | with \|.

export interface AuditEntry {
  seq: number;
  epoch: string | null;
  timestamp: string;
  eventType: string;
  actor: string;
  datasetRef: string;
  statedPurpose: string;
  plainLanguage: string;
}

export function escape(value: string): string {
  return value.replace(/\\/g, '\\\\').replace(/\|/g, '\\|');
}

export function canonicalBytes(entry: AuditEntry): Uint8Array {
  const joined = [
    escape(String(entry.seq)),
    escape(entry.epoch ?? ''),
    escape(entry.timestamp),
    escape(entry.eventType),
    escape(entry.actor),
    escape(entry.datasetRef),
    escape(entry.statedPurpose),
    escape(entry.plainLanguage),
  ].join('|');
  return new TextEncoder().encode(joined);
}

function toHex(buffer: ArrayBuffer): string {
  return Array.from(new Uint8Array(buffer)).map((b) => b.toString(16).padStart(2, '0')).join('');
}

function hexToBytes(hex: string): Uint8Array {
  const bytes = new Uint8Array(hex.length / 2);
  for (let i = 0; i < bytes.length; i++) {
    bytes[i] = parseInt(hex.substring(i * 2, i * 2 + 2), 16);
  }
  return bytes;
}

async function sha256Hex(data: Uint8Array): Promise<string> {
  return toHex(await crypto.subtle.digest('SHA-256', data));
}

export async function leafHash(entry: AuditEntry): Promise<string> {
  const body = canonicalBytes(entry);
  const domain = new Uint8Array(body.length + 1);
  domain[0] = 0x00;
  domain.set(body, 1);
  return sha256Hex(domain);
}

export async function nodeHash(left: string, right: string): Promise<string> {
  const leftBytes = hexToBytes(left);
  const rightBytes = hexToBytes(right);
  const combined = new Uint8Array(1 + leftBytes.length + rightBytes.length);
  combined[0] = 0x01;
  combined.set(leftBytes, 1);
  combined.set(rightBytes, 1 + leftBytes.length);
  return sha256Hex(combined);
}

export async function prevEpochHash(epochId: string, merkleRoot: string, sealedAt: string): Promise<string> {
  const joined = [escape(epochId), escape(merkleRoot), escape(sealedAt)].join('|');
  return sha256Hex(new TextEncoder().encode(joined));
}

export const GENESIS_PREV_EPOCH_HASH: Promise<string> = sha256Hex(new Uint8Array(0));
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `npx vitest run test/hashing.test.ts` (from `packages/audit-verify`)
Expected: PASS, all 7 tests.

- [ ] **Step 6: Write the failing merkle test**

Create `packages/audit-verify/test/merkle.test.ts`:

```typescript
import { describe, expect, it } from 'vitest';
import { verifyInclusion } from '../src/merkle.js';
import { leafHash } from '../src/hashing.js';
import type { AuditEntry } from '../src/hashing.js';
import type { MerkleProof } from '../src/merkle.js';

const entry: AuditEntry = {
  seq: 0, epoch: 'epoch-1', timestamp: 't', eventType: 'type', actor: 'actor',
  datasetRef: 'asset', statedPurpose: 'purpose', plainLanguage: 'plain',
};

describe('verifyInclusion', () => {
  it('accepts a single-leaf tree where the leaf is the root and there are no steps', async () => {
    const leaf = await leafHash(entry);
    const proof: MerkleProof = { steps: [] };
    expect(await verifyInclusion(leaf, proof, leaf)).toBe(true);
  });

  it('rejects a proof against the wrong root', async () => {
    const leaf = await leafHash(entry);
    const proof: MerkleProof = { steps: [] };
    expect(await verifyInclusion(leaf, proof, '0'.repeat(64))).toBe(false);
  });

  it('rejects a tampered sibling hash', async () => {
    const leaf = await leafHash(entry);
    const other: AuditEntry = { ...entry, seq: 1 };
    const otherLeaf = await leafHash(other);
    const validProof: MerkleProof = { steps: [{ siblingHash: otherLeaf, siblingIsRight: true }] };
    const root = await import('../src/hashing.js').then((m) => m.nodeHash(leaf, otherLeaf));
    expect(await verifyInclusion(leaf, validProof, root)).toBe(true);

    const tamperedProof: MerkleProof = { steps: [{ siblingHash: 'f'.repeat(64), siblingIsRight: true }] };
    expect(await verifyInclusion(leaf, tamperedProof, root)).toBe(false);
  });
});
```

- [ ] **Step 7: Run the test to verify it fails**

Run: `npx vitest run test/merkle.test.ts`
Expected: FAIL, `src/merkle.ts` does not exist yet.

- [ ] **Step 8: Write merkle.ts**

Create `packages/audit-verify/src/merkle.ts`:

```typescript
import { nodeHash } from './hashing.js';

export interface ProofStep {
  siblingHash: string;
  siblingIsRight: boolean;
}

export interface MerkleProof {
  steps: ProofStep[];
}

// The mirror of MerkleTree.verify in connector/extensions/benefits-audit. Deliberately
// has no root()/proof() builder counterpart: the server (EpochSealer, AuditResource)
// is the only party that ever builds a tree; a participant device only ever verifies
// one it was handed.
export async function verifyInclusion(leaf: string, proof: MerkleProof, root: string): Promise<boolean> {
  let current = leaf;
  for (const step of proof.steps) {
    current = step.siblingIsRight
      ? await nodeHash(current, step.siblingHash)
      : await nodeHash(step.siblingHash, current);
  }
  return current === root;
}
```

- [ ] **Step 9: Run the test to verify it passes**

Run: `npx vitest run test/merkle.test.ts`
Expected: PASS, all 3 tests.

- [ ] **Step 10: Write the failing chain test**

Create `packages/audit-verify/test/chain.test.ts`:

```typescript
import { describe, expect, it } from 'vitest';
import { verifyChain } from '../src/chain.js';
import { GENESIS_PREV_EPOCH_HASH, prevEpochHash } from '../src/hashing.js';
import type { Epoch } from '../src/chain.js';

describe('verifyChain', () => {
  it('accepts an empty chain', async () => {
    expect(await verifyChain([])).toBe(true);
  });

  it('accepts a correctly linked two-epoch chain', async () => {
    const first: Epoch = { epochId: 'e1', merkleRoot: 'root-1', prevEpochHash: await GENESIS_PREV_EPOCH_HASH, sealedAt: 't1' };
    const second: Epoch = {
      epochId: 'e2', merkleRoot: 'root-2',
      prevEpochHash: await prevEpochHash(first.epochId, first.merkleRoot, first.sealedAt), sealedAt: 't2',
    };
    expect(await verifyChain([first, second])).toBe(true);
  });

  it('rejects a chain whose first epoch does not point at the genesis hash', async () => {
    const first: Epoch = { epochId: 'e1', merkleRoot: 'root-1', prevEpochHash: '0'.repeat(64), sealedAt: 't1' };
    expect(await verifyChain([first])).toBe(false);
  });

  it('rejects two reordered epochs', async () => {
    const first: Epoch = { epochId: 'e1', merkleRoot: 'root-1', prevEpochHash: await GENESIS_PREV_EPOCH_HASH, sealedAt: 't1' };
    const second: Epoch = {
      epochId: 'e2', merkleRoot: 'root-2',
      prevEpochHash: await prevEpochHash(first.epochId, first.merkleRoot, first.sealedAt), sealedAt: 't2',
    };
    expect(await verifyChain([second, first])).toBe(false);
  });

  it('rejects a mutated root in an earlier epoch', async () => {
    const first: Epoch = { epochId: 'e1', merkleRoot: 'root-1', prevEpochHash: await GENESIS_PREV_EPOCH_HASH, sealedAt: 't1' };
    const second: Epoch = {
      epochId: 'e2', merkleRoot: 'root-2',
      prevEpochHash: await prevEpochHash(first.epochId, first.merkleRoot, first.sealedAt), sealedAt: 't2',
    };
    const mutatedFirst: Epoch = { ...first, merkleRoot: 'tampered-root' };
    expect(await verifyChain([mutatedFirst, second])).toBe(false);
  });
});
```

- [ ] **Step 11: Run the test to verify it fails**

Run: `npx vitest run test/chain.test.ts`
Expected: FAIL, `src/chain.ts` does not exist yet.

- [ ] **Step 12: Write chain.ts and index.ts**

Create `packages/audit-verify/src/chain.ts`:

```typescript
import { prevEpochHash, GENESIS_PREV_EPOCH_HASH } from './hashing.js';

export interface Epoch {
  epochId: string;
  merkleRoot: string;
  prevEpochHash: string;
  sealedAt: string;
}

// epochs must be oldest-first, matching AuditStore.epochChain's own ordering.
export async function verifyChain(epochs: Epoch[]): Promise<boolean> {
  let expectedPrev = await GENESIS_PREV_EPOCH_HASH;
  for (const epoch of epochs) {
    if (epoch.prevEpochHash !== expectedPrev) {
      return false;
    }
    expectedPrev = await prevEpochHash(epoch.epochId, epoch.merkleRoot, epoch.sealedAt);
  }
  return true;
}
```

Create `packages/audit-verify/src/index.ts`:

```typescript
export { escape, canonicalBytes, leafHash, nodeHash, prevEpochHash, GENESIS_PREV_EPOCH_HASH } from './hashing.js';
export type { AuditEntry } from './hashing.js';
export { verifyInclusion } from './merkle.js';
export type { MerkleProof, ProofStep } from './merkle.js';
export { verifyChain } from './chain.js';
export type { Epoch } from './chain.js';
```

- [ ] **Step 13: Run the full package test suite**

Run: `cd packages/audit-verify && npx vitest run`
Expected: PASS, all 15 tests across the three test files.

- [ ] **Step 14: Commit**

```bash
git add packages/audit-verify
git commit -m "Add packages/audit-verify, the dependency-free TypeScript participant verifier"
```

---

### Task 9: Cross-language conformance vectors

**Files:**
- Create: `conformance/audit-vectors/av001-single-leaf.json`
- Create: `conformance/audit-vectors/av002-two-leaves.json`
- Create: `conformance/audit-vectors/av003-three-leaves-odd-promotion.json`
- Create: `conformance/audit-vectors/av004-five-leaves.json`
- Create: `conformance/audit-vectors/av005-separator-in-plain-language.json`
- Create: `conformance/audit-vectors/av006-backslash-in-actor.json`
- Create: `connector/extensions/benefits-audit/src/test/java/org/benefits/audit/AuditConformanceVectorTest.java`
- Create: `packages/audit-verify/test/conformance.test.ts`
- Modify: `conformance/README.md`

**Interfaces:**
- Consumes: `AuditHashing.leafHash`, `MerkleTree.root` (Java, Tasks 2-3); `leafHash` (TypeScript, Task 8).
- Produces: the proof that Java and TypeScript compute byte-identical leaf hashes and roots, closing spec section 3.1's stated requirement.

Each vector fixes a small list of entries and the expected leaf hash of the first entry plus the expected root over the whole list, so both language runners can be pure functions of the vector file with no other fixture.

- [ ] **Step 1: Compute the vectors' expected values**

Vector values must be computed, not guessed, write a short throwaway script using the Java implementation from Task 2/3 (already built and tested) to print real SHA-256 hex digests, then paste those exact values into the vector files. Do not hand-invent hex strings.

```bash
cat > /tmp/PrintVectors.java <<'JAVA'
import org.benefits.audit.AuditEntry;
import org.benefits.audit.AuditHashing;
import org.benefits.audit.MerkleTree;
import java.util.List;

public class PrintVectors {
    static AuditEntry e(long seq, String epoch, String ts, String type, String actor, String asset, String purpose, String plain) {
        return new AuditEntry(seq, epoch, ts, type, actor, asset, purpose, plain);
    }

    public static void main(String[] args) {
        print("av001-single-leaf", List.of(e(0, "epoch-1", "t0", "type", "actor-0", "asset-0", "purpose", "plain-0")));
        print("av002-two-leaves", List.of(
                e(0, "epoch-1", "t0", "type", "actor-0", "asset-0", "purpose", "plain-0"),
                e(1, "epoch-1", "t1", "type", "actor-1", "asset-1", "purpose", "plain-1")));
        print("av003-three-leaves-odd-promotion", List.of(
                e(0, "epoch-1", "t0", "type", "actor-0", "asset-0", "purpose", "plain-0"),
                e(1, "epoch-1", "t1", "type", "actor-1", "asset-1", "purpose", "plain-1"),
                e(2, "epoch-1", "t2", "type", "actor-2", "asset-2", "purpose", "plain-2")));
        print("av004-five-leaves", List.of(
                e(0, "epoch-1", "t0", "type", "actor-0", "asset-0", "purpose", "plain-0"),
                e(1, "epoch-1", "t1", "type", "actor-1", "asset-1", "purpose", "plain-1"),
                e(2, "epoch-1", "t2", "type", "actor-2", "asset-2", "purpose", "plain-2"),
                e(3, "epoch-1", "t3", "type", "actor-3", "asset-3", "purpose", "plain-3"),
                e(4, "epoch-1", "t4", "type", "actor-4", "asset-4", "purpose", "plain-4")));
        print("av005-separator-in-plain-language", List.of(
                e(0, "epoch-1", "t0", "type", "actor-0", "asset-0", "purpose", "a|b|c"),
                e(1, "epoch-1", "t1", "type", "actor-1", "asset-1", "purpose", "plain-1")));
        print("av006-backslash-in-actor", List.of(
                e(0, "epoch-1", "t0", "type", "actor\\with\\backslash", "asset-0", "purpose", "plain-0"),
                e(1, "epoch-1", "t1", "type", "actor-1", "asset-1", "purpose", "plain-1")));
    }

    static void print(String id, List<AuditEntry> entries) {
        var leaves = entries.stream().map(AuditHashing::leafHash).toList();
        System.out.println(id + " firstLeafHash=" + leaves.get(0) + " root=" + MerkleTree.root(leaves));
    }
}
JAVA
javac -cp connector/extensions/benefits-audit/build/classes/java/main /tmp/PrintVectors.java -d /tmp
java -cp /tmp:connector/extensions/benefits-audit/build/classes/java/main PrintVectors
```

Run this and capture its six output lines before writing the vector files below, the `firstLeafHash` and `root` values in each JSON file below are placeholders (`REPLACE_WITH_COMPUTED_VALUE`) that must be replaced with this script's real output.

- [ ] **Step 2: Write the vector files**

Create `conformance/audit-vectors/av001-single-leaf.json`:

```json
{
  "id": "av001-single-leaf",
  "description": "a single-entry tree; the root is that entry's own leaf hash",
  "entries": [
    { "seq": 0, "epoch": "epoch-1", "timestamp": "t0", "eventType": "type", "actor": "actor-0", "datasetRef": "asset-0", "statedPurpose": "purpose", "plainLanguage": "plain-0" }
  ],
  "expected": { "firstLeafHash": "REPLACE_WITH_COMPUTED_VALUE", "root": "REPLACE_WITH_COMPUTED_VALUE" }
}
```

Create `conformance/audit-vectors/av002-two-leaves.json`:

```json
{
  "id": "av002-two-leaves",
  "description": "an even-sized tree, one pairing, no promotion",
  "entries": [
    { "seq": 0, "epoch": "epoch-1", "timestamp": "t0", "eventType": "type", "actor": "actor-0", "datasetRef": "asset-0", "statedPurpose": "purpose", "plainLanguage": "plain-0" },
    { "seq": 1, "epoch": "epoch-1", "timestamp": "t1", "eventType": "type", "actor": "actor-1", "datasetRef": "asset-1", "statedPurpose": "purpose", "plainLanguage": "plain-1" }
  ],
  "expected": { "firstLeafHash": "REPLACE_WITH_COMPUTED_VALUE", "root": "REPLACE_WITH_COMPUTED_VALUE" }
}
```

Create `conformance/audit-vectors/av003-three-leaves-odd-promotion.json`:

```json
{
  "id": "av003-three-leaves-odd-promotion",
  "description": "an odd-sized tree; the third leaf is promoted unhashed to the second level",
  "entries": [
    { "seq": 0, "epoch": "epoch-1", "timestamp": "t0", "eventType": "type", "actor": "actor-0", "datasetRef": "asset-0", "statedPurpose": "purpose", "plainLanguage": "plain-0" },
    { "seq": 1, "epoch": "epoch-1", "timestamp": "t1", "eventType": "type", "actor": "actor-1", "datasetRef": "asset-1", "statedPurpose": "purpose", "plainLanguage": "plain-1" },
    { "seq": 2, "epoch": "epoch-1", "timestamp": "t2", "eventType": "type", "actor": "actor-2", "datasetRef": "asset-2", "statedPurpose": "purpose", "plainLanguage": "plain-2" }
  ],
  "expected": { "firstLeafHash": "REPLACE_WITH_COMPUTED_VALUE", "root": "REPLACE_WITH_COMPUTED_VALUE" }
}
```

Create `conformance/audit-vectors/av004-five-leaves.json`:

```json
{
  "id": "av004-five-leaves",
  "description": "a larger odd-sized tree spanning two levels of promotion",
  "entries": [
    { "seq": 0, "epoch": "epoch-1", "timestamp": "t0", "eventType": "type", "actor": "actor-0", "datasetRef": "asset-0", "statedPurpose": "purpose", "plainLanguage": "plain-0" },
    { "seq": 1, "epoch": "epoch-1", "timestamp": "t1", "eventType": "type", "actor": "actor-1", "datasetRef": "asset-1", "statedPurpose": "purpose", "plainLanguage": "plain-1" },
    { "seq": 2, "epoch": "epoch-1", "timestamp": "t2", "eventType": "type", "actor": "actor-2", "datasetRef": "asset-2", "statedPurpose": "purpose", "plainLanguage": "plain-2" },
    { "seq": 3, "epoch": "epoch-1", "timestamp": "t3", "eventType": "type", "actor": "actor-3", "datasetRef": "asset-3", "statedPurpose": "purpose", "plainLanguage": "plain-3" },
    { "seq": 4, "epoch": "epoch-1", "timestamp": "t4", "eventType": "type", "actor": "actor-4", "datasetRef": "asset-4", "statedPurpose": "purpose", "plainLanguage": "plain-4" }
  ],
  "expected": { "firstLeafHash": "REPLACE_WITH_COMPUTED_VALUE", "root": "REPLACE_WITH_COMPUTED_VALUE" }
}
```

Create `conformance/audit-vectors/av005-separator-in-plain-language.json`:

```json
{
  "id": "av005-separator-in-plain-language",
  "description": "a field value containing the raw separator character, proving both languages escape it identically",
  "entries": [
    { "seq": 0, "epoch": "epoch-1", "timestamp": "t0", "eventType": "type", "actor": "actor-0", "datasetRef": "asset-0", "statedPurpose": "purpose", "plainLanguage": "a|b|c" },
    { "seq": 1, "epoch": "epoch-1", "timestamp": "t1", "eventType": "type", "actor": "actor-1", "datasetRef": "asset-1", "statedPurpose": "purpose", "plainLanguage": "plain-1" }
  ],
  "expected": { "firstLeafHash": "REPLACE_WITH_COMPUTED_VALUE", "root": "REPLACE_WITH_COMPUTED_VALUE" }
}
```

Create `conformance/audit-vectors/av006-backslash-in-actor.json`:

```json
{
  "id": "av006-backslash-in-actor",
  "description": "a field value containing a raw backslash, proving both languages apply backslash-before-pipe escaping identically",
  "entries": [
    { "seq": 0, "epoch": "epoch-1", "timestamp": "t0", "eventType": "type", "actor": "actor\\with\\backslash", "datasetRef": "asset-0", "statedPurpose": "purpose", "plainLanguage": "plain-0" },
    { "seq": 1, "epoch": "epoch-1", "timestamp": "t1", "eventType": "type", "actor": "actor-1", "datasetRef": "asset-1", "statedPurpose": "purpose", "plainLanguage": "plain-1" }
  ],
  "expected": { "firstLeafHash": "REPLACE_WITH_COMPUTED_VALUE", "root": "REPLACE_WITH_COMPUTED_VALUE" }
}
```

Replace every `REPLACE_WITH_COMPUTED_VALUE` with the real hex digest from Step 1's script output before continuing, a vector file with a placeholder value left in it is a plan defect, not a passable test.

- [ ] **Step 3: Write the Java conformance runner**

Create `connector/extensions/benefits-audit/src/test/java/org/benefits/audit/AuditConformanceVectorTest.java`:

```java
package org.benefits.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AuditConformanceVectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path vectorDir() {
        return Path.of(System.getProperty("user.dir")).resolve("../../../conformance/audit-vectors").normalize();
    }

    @TestFactory
    Stream<DynamicTest> everyVectorHashesIdentically() throws IOException {
        try (var files = Files.list(vectorDir())) {
            List<Path> paths = files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            assertThat(paths).isNotEmpty();
            return paths.stream().map(path -> DynamicTest.dynamicTest(path.getFileName().toString(), () -> {
                JsonNode vector = MAPPER.readTree(path.toFile());
                List<AuditEntry> entries = new ArrayList<>();
                for (var node : vector.get("entries")) {
                    entries.add(new AuditEntry(
                            node.get("seq").asLong(), node.get("epoch").asText(), node.get("timestamp").asText(),
                            node.get("eventType").asText(), node.get("actor").asText(), node.get("datasetRef").asText(),
                            node.get("statedPurpose").asText(), node.get("plainLanguage").asText()));
                }
                var leaves = entries.stream().map(AuditHashing::leafHash).toList();
                assertThat(leaves.get(0)).isEqualTo(vector.get("expected").get("firstLeafHash").asText());
                assertThat(MerkleTree.root(leaves)).isEqualTo(vector.get("expected").get("root").asText());
            }));
        }
    }
}
```

- [ ] **Step 4: Run the Java conformance test**

Run: `./gradlew :connector:extensions:benefits-audit:test --tests AuditConformanceVectorTest`
Expected: PASS, 6 dynamic tests (one per vector file).

- [ ] **Step 5: Write the TypeScript conformance runner**

Create `packages/audit-verify/test/conformance.test.ts`:

```typescript
import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { leafHash } from '../src/hashing.js';
import type { AuditEntry } from '../src/hashing.js';

const VECTOR_DIR = join(import.meta.dirname, '../../../conformance/audit-vectors');

describe('audit hashing conformance vectors', () => {
  const files = readdirSync(VECTOR_DIR).filter((f) => f.endsWith('.json')).sort();
  expect(files.length).toBeGreaterThan(0);

  for (const file of files) {
    it(`${file} hashes to the vector's expected values`, async () => {
      const vector = JSON.parse(readFileSync(join(VECTOR_DIR, file), 'utf-8'));
      const entries: AuditEntry[] = vector.entries;
      const firstLeaf = await leafHash(entries[0]);
      expect(firstLeaf).toBe(vector.expected.firstLeafHash);
    });
  }
});
```

This proves `leafHash` agrees for every vector's first entry; the full `root` check is intentionally Java-only here, because building a root in TypeScript would require porting `MerkleTree.root` into `packages/audit-verify` too, and spec section 8 deliberately keeps that package to verification only (`verifyInclusion`, not tree construction) since a participant device never builds a tree, only checks a proof against one. `leafHash` agreeing across languages, combined with `MerkleTree.verify`/`verifyInclusion` each being proven correct against `MerkleTree.root`/`MerkleTree.proof` independently within their own language (Tasks 3 and 8), is what makes the end-to-end claim hold: whatever root the Java sealer computes, a TypeScript verifier that recomputes the same leaf hash and combines it with the server's proof steps will reach that same root.

- [ ] **Step 6: Run the TypeScript conformance test**

Run: `cd packages/audit-verify && npx vitest run test/conformance.test.ts`
Expected: PASS, 6 tests (one per vector file).

- [ ] **Step 7: Add a section to conformance/README.md**

Append to `conformance/README.md`:

```markdown
## Audit hashing vectors

`conformance/audit-vectors/` is a second, independent conformance contract from the
one above: it pins `AuditHashing.leafHash` and `MerkleTree.root`
(`connector/extensions/benefits-audit`) against the equivalent TypeScript functions
in `packages/audit-verify/src/hashing.ts`. These vectors have nothing to do with
consent semantics; they exist because a participant's TypeScript verifier and the
connector's Java sealer must compute byte-identical hashes for an inclusion proof to
ever verify. Each vector fixes a small list of `AuditEntry` values and the expected
leaf hash of the first entry plus the expected Merkle root over the whole list.
`AuditConformanceVectorTest.java` and `packages/audit-verify/test/conformance.test.ts`
both run every vector in this directory.
```

- [ ] **Step 8: Commit**

```bash
git add conformance/audit-vectors conformance/README.md \
  connector/extensions/benefits-audit/src/test/java/org/benefits/audit/AuditConformanceVectorTest.java \
  packages/audit-verify/test/conformance.test.ts
git commit -m "Add cross-language audit hashing conformance vectors"
```

---

### Task 10: End-to-end gate

**Files:**
- Create: `tools/probe-audit-e2e.sh`
- Create: `tools/verify-audit-response.mjs`

**Interfaces:**
- Consumes: everything from Tasks 1-9 running together in a real connector process.
- Produces: the phase's Gate 4 result, a real negotiation and transfer through the running connector, a sealed epoch, `GET /audit/{participantRef}` returning that participant's entries and proofs, and the TypeScript verifier from Task 8 confirming both inclusion and chain integrity given only that HTTP response.

- [ ] **Step 1: Write the verification script**

Create `tools/verify-audit-response.mjs`:

```javascript
import { leafHash, verifyInclusion, verifyChain } from '../packages/audit-verify/src/index.ts';

const url = process.argv[2];
const participantRef = process.argv[3];

const response = await fetch(`${url}/${participantRef}`);
if (!response.ok) {
  console.error(`audit endpoint returned ${response.status}`);
  process.exit(1);
}
const body = await response.json();

if (body.entries.length === 0) {
  console.error('expected at least one entry for participant ' + participantRef);
  process.exit(1);
}

const chainOk = await verifyChain(body.epochs);
if (!chainOk) {
  console.error('epoch chain did not verify');
  process.exit(1);
}

let sealedCount = 0;
for (const entry of body.entries) {
  if (entry.epoch === null) {
    continue;
  }
  sealedCount++;
  const proof = body.proofs[entry.seq];
  const epoch = body.epochs.find((e) => e.epochId === entry.epoch);
  if (!epoch) {
    console.error(`no epoch found for entry seq=${entry.seq} epoch=${entry.epoch}`);
    process.exit(1);
  }
  const leaf = await leafHash(entry);
  const ok = await verifyInclusion(leaf, proof, epoch.merkleRoot);
  if (!ok) {
    console.error(`inclusion proof failed for entry seq=${entry.seq}`);
    process.exit(1);
  }
}

if (sealedCount === 0) {
  console.error('no sealed entries were returned; nothing was actually verified');
  process.exit(1);
}

console.log(`verified ${sealedCount} sealed entr${sealedCount === 1 ? 'y' : 'ies'} across ${body.epochs.length} epoch(s)`);
```

Running a `.ts` module directly with plain `node` requires either Node's built-in TypeScript stripping (available in Node 22.6+ behind no flag for simple type-only syntax, but this file imports named exports from a `.ts` source, which needs the import path resolved) or a quick build. Prefer building `audit-verify` first and importing the built output instead of the source:

```javascript
import { leafHash, verifyInclusion, verifyChain } from '../packages/audit-verify/dist/index.js';
```

and note in the task report that `packages/audit-verify` must be built (`npm run build`, from that package directory) before this script runs. Use whichever of the two import paths actually resolves under this repo's installed Node version, try the `dist/index.js` form first, since it does not depend on experimental TypeScript support.

- [ ] **Step 2: Write the end-to-end probe script**

Create `tools/probe-audit-e2e.sh`. This drives BOTH a negotiation and a real transfer
- an earlier draft of this script covered negotiation only, which a task reviewer
caught as falling short of what this gate (and the design spec's own Gate 4
definition) actually promises to prove. The transfer section below reuses the
identical, already-proven sink-server and transfer-request shape
`tools/probe-audit-events.sh` (Task 6) established for the same purpose:

```bash
#!/usr/bin/env bash
# Gate 4: a real negotiation and transfer through the running connector produce two
# sealed epochs, and the TypeScript verifier confirms both from the HTTP response
# alone.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

WORK=/tmp/benefits-audit-e2e
rm -rf "$WORK" && mkdir -p "$WORK"

cat > "$WORK/policy.json" <<'JSON'
{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@id": "benefits-policy",
  "policy": {
    "@context": [ "http://www.w3.org/ns/odrl.jsonld" ],
    "@type": "Set",
    "permission": [ { "action": "use" } ],
    "prohibition": [], "obligation": []
  }
}
JSON

cat > "$WORK/artifacts.json" <<'JSON'
[{ "releaseRequestId": "rr-audit-e2e", "payload": "{\"groups\":[{\"group\":\"app\",\"count\":6.4821}]}",
   "computedK": 6, "epsilonCharged": 0.5 }]
JSON

# A trivial sink for the transfer below (see tools/probe-aggregate-transfer.sh and
# tools/probe-audit-events.sh, whose identical shape this reuses): python3 -m
# http.server only implements do_GET/do_HEAD, and HttpData-PUSH sends the payload via
# HTTP POST.
cat > "$WORK/sink_server.py" <<'PY'
import http.server
import os
import sys

work = sys.argv[1]


class Handler(http.server.BaseHTTPRequestHandler):
    def _read_chunked(self):
        body = b''
        while True:
            size_line = self.rfile.readline().strip()
            size = int(size_line.split(b';')[0], 16)
            if size == 0:
                self.rfile.readline()
                break
            body += self.rfile.read(size)
            self.rfile.readline()
        return body

    def _receive(self):
        if self.headers.get('Transfer-Encoding', '').lower() == 'chunked':
            body = self._read_chunked()
        else:
            length = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(length)
        with open(os.path.join(work, 'received.data'), 'wb') as f:
            f.write(body)
        self.send_response(200)
        self.end_headers()

    def do_POST(self):
        self._receive()

    def do_PUT(self):
        self._receive()

    def log_message(self, fmt, *args):
        pass


http.server.HTTPServer(('127.0.0.1', 4000), Handler).serve_forever()
PY

SINK_PID=""
start_sink() {
  python3 "$WORK/sink_server.py" "$WORK" >/dev/null 2>&1 &
  SINK_PID=$!
  for _ in $(seq 1 30); do
    port_free 4000 || break
    sleep 1
  done
  if port_free 4000; then
    echo "sink failed to bind port 4000" >&2
    exit 1
  fi
}
stop_sink() {
  [ -n "$SINK_PID" ] && kill "$SINK_PID" 2>/dev/null || true
  for _ in $(seq 1 30); do
    port_free 4000 && break
    sleep 1
  done
  SINK_PID=""
}

trap 'stop_sink; stop_all' EXIT
stop_all
ensure_couchdb
# Wipe any prior run's audit state first, otherwise leftover entries for actor
# "consumer" from an earlier run of this script, or from Task 6's own probe (which
# uses the same participant id), inflate this run's sealed-entry count and make the
# "2 sealed entries" expectation below unreliable. Task 6's probe established this
# same pattern for the same reason.
curl -s -o /dev/null -X DELETE "http://admin:benefits@localhost:5984/benefits-audit"
# EPOCH_SIZE=1 so each event seals its own epoch immediately, rather than needing 50
# real events to accumulate before this probe can observe a seal. The asset is
# BenefitsAggregate-typed (backed by a seeded ReleaseArtifact) from the start, not a
# plain "test" type, so the same negotiation this script drives can also be
# transferred afterward, a "test"-type asset has no registered DataSourceFactory and
# a transfer against it would fail at the data plane step.
start_connector provider "$ROOT/connector/runtime/provider.properties" 19193 \
  -Dbenefits.audit.epochSize=1 -Dbenefits.seed.artifacts="$WORK/artifacts.json"
start_connector consumer "$ROOT/connector/runtime/consumer.properties" 29193

curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/assets" -d '{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@id": "audit-e2e-asset",
  "properties": { "name": "audit e2e asset" },
  "dataAddress": { "type": "BenefitsAggregate", "releaseRequestId": "rr-audit-e2e" }
}' >/dev/null
curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/policydefinitions" -d @"$WORK/policy.json" >/dev/null
curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/contractdefinitions" -d '{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@id": "audit-e2e-definition",
  "accessPolicyId": "benefits-policy",
  "contractPolicyId": "benefits-policy",
  "assetsSelector": []
}' >/dev/null

STATE=$(negotiate "$WORK/policy.json")
echo "negotiation: $STATE"
[ "$STATE" = "FINALIZED" ] || { echo "negotiation did not finalize" >&2; exit 1; }

AGREEMENT=$(curl -sf -H "$KEY" "$CONSUMER_MGMT/v3/contractnegotiations/$(cat /tmp/negotiation-id.txt)" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["contractAgreementId"])')

start_sink
TP=$(curl -sf -H "$KEY" -H "$JSON" -X POST "$CONSUMER_MGMT/v3/transferprocesses" -d "{
  \"@context\": { \"@vocab\": \"https://w3id.org/edc/v0.0.1/ns/\" },
  \"@type\": \"TransferRequestDto\",
  \"connectorId\": \"provider\",
  \"counterPartyAddress\": \"http://localhost:19194/protocol/2025-1\",
  \"contractId\": \"$AGREEMENT\",
  \"protocol\": \"dataspace-protocol-http:2025-1\",
  \"transferType\": \"HttpData-PUSH\",
  \"dataDestination\": { \"type\": \"HttpData\", \"baseUrl\": \"http://localhost:4000/\" }
}")
TP_ID=$(echo "$TP" | python3 -c 'import json,sys; print(json.load(sys.stdin)["@id"])')
for _ in $(seq 1 60); do
  TSTATE=$(curl -sf -H "$KEY" "$CONSUMER_MGMT/v3/transferprocesses/$TP_ID" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin).get("state",""))')
  case "$TSTATE" in COMPLETED|TERMINATED) break ;; esac
  sleep 1
done
stop_sink
echo "transfer: $TSTATE"
[ "$TSTATE" = "COMPLETED" ] || { echo "transfer did not reach COMPLETED (state=$TSTATE)" >&2; exit 1; }

# Both the negotiation-finalized and transfer-completed events fire asynchronously
# relative to their respective management-API state reaching a terminal value, so
# give the audit extension a moment to record and seal both epochs before querying.
sleep 2

echo "--- fetching audit response for consumer ---"
curl -sf "http://localhost:19197/audit/consumer" | tee "$WORK/response.json"
echo

(cd "$ROOT/packages/audit-verify" && npm run build >/dev/null)
node "$ROOT/tools/verify-audit-response.mjs" "http://localhost:19197/audit" consumer
```

`consumer` is confirmed correct, not assumed: Task 6's own probe run recorded real `AuditEntry` documents with `actor=consumer` for exactly this negotiation flow (see `docs/superpowers/plans/...` Task 6 history, or just re-derive it, `negotiate()` in `tools/lib.sh` sends the consumer's own `counterPartyId` claim as `"provider"` to the provider, but the PROVIDER's own `ContractNegotiationFinalized.getCounterPartyId()` reports the REQUESTER's participant id, which is `consumer.properties`'s `edc.participant.id=consumer`). `AuditResource` filters strictly by `actor` equality, so this must be exact. The transfer correlates to the same `actor` via the agreement-id cache (spec section 6, fixed in Task 6), since `event.getContractId()` here is the same `AGREEMENT` value the negotiation produced.

- [ ] **Step 3: Run the probe**

Run: `chmod +x tools/probe-audit-e2e.sh && ./gradlew :connector:runtime:shadowJar && ./tools/probe-audit-e2e.sh`
Expected: `negotiation: FINALIZED`, `transfer: COMPLETED`, a non-empty JSON response from the audit endpoint, and `verified 2 sealed entries across 2 epoch(s)` as the final line, one epoch for the negotiation-finalized entry, one for the transfer-completed entry, since `epochSize=1` seals each individually. `verifyChain`'s link between those two real epochs (not a synthetic one, unlike Task 4/8's unit tests) is exactly the property a negotiation-only run could never exercise.

- [ ] **Step 4: Commit**

```bash
git add tools/probe-audit-e2e.sh tools/verify-audit-response.mjs
git commit -m "Add phase 4 end-to-end gate: real negotiation to verified audit proof"
```

---

## After all tasks

Run the full suite once more end to end before declaring the phase done:

```bash
./gradlew test
(cd packages/audit-verify && npx vitest run)
./tools/probe-audit-events.sh
./tools/probe-audit-e2e.sh
```

All four must be green. Then proceed to the whole-branch review per superpowers:subagent-driven-development.
