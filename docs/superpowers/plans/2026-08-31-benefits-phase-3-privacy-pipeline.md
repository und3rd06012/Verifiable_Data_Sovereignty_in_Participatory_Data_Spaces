# BENEFITS Phase 3: Privacy Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the anonymisation stage of the release path (k-anonymity, Laplace differential privacy, and a per-site privacy budget accountant) enforced inside the Eclipse Dataspace Components connector, so aggregated data cannot leave without passing through it.

**Architecture:** A `DataSourceFactory` registered with EDC's `PipelineService` serves a BENEFITS-specific DataAddress type. The anonymised artifact is computed ONCE when a release request is created, stored, and served byte-identically on every transfer. Two new ODRL constraint functions read the stored artifact's computed k and reserved epsilon during contract negotiation, before any transfer exists.

**Tech Stack:** Java 17, Eclipse Dataspace Components 0.18.0, Gradle wrapper 9.7.1 (vendored), JUnit 5, AssertJ, CouchDB 3.5.2 (pinned by digest).

**Spec:** `docs/superpowers/specs/2026-08-31-benefits-privacy-pipeline-design.md`
**Parent spec:** `docs/superpowers/specs/2026-08-30-benefits-reference-implementation-design.md`

## Global Constraints

- EDC pinned to exactly **0.18.0**. Never float it. No dependency version may change without saying so.
- Gradle is NOT installed. Use only the vendored `./gradlew`.
- Java toolchain 17, declared explicitly (21 is the machine default).
- Package for all new Java: `org.benefits.policy`.
- **Numeric ODRL right operands must be emitted as STRINGS.** EDC's management API validator throws `ClassCastException: JsonIntNumber cannot be cast to JsonString` on a numeric `rightOperand`. `benefits:kAnonymity` emits `"5"`, `benefits:epsilonBudget` emits `"0.5"`.
- **A left operand bound to a scope but with no registered function is rejected at policy-creation time with HTTP 400.** Constraint functions, scope bindings, `CompileOptions` fields and compiler emission must therefore land as ONE change (Task 8), never split.
- Every constraint function **fails closed** on every uncertain path and calls `context.reportProblem(...)` before returning false, matching the existing family.
- All timestamps are ISO 8601 UTC with millisecond precision and a trailing `Z`.
- The DP mechanism's RNG is **injectable**: seeded in tests, secure in production.
- CouchDB credentials live in configuration under `benefits.couchdb.*`, never in the repository.
- No emoji anywhere in code, comments, log strings or commit messages.
- No AI or tooling attribution in commits, comments or docs. No `Co-Authored-By` trailers.
- No em-dashes in prose written into the repo. Use commas, colons or periods.
- Conventional commit prefixes. Commit with:
  `git -c user.email=antoniosntib012@gmail.com -c user.name="Antonios Ntib" commit ...`

---

## File Structure

```
connector/extensions/benefits-privacy/          NEW Gradle module
  build.gradle.kts
  src/main/java/org/benefits/policy/
    Contribution.java                 one contributed record, as the pipeline sees it
    ContributionSource.java           interface; the testability seam
    InMemoryContributionSource.java   test double, also used by the probe
    CouchDbContributionSource.java    the real reader (Task 10)
    GroupedCount.java                 one output row: group key plus count
    QuerySpec.java                    which fields to group by, and the site
    KAnonymity.java                   dedup by participant, group, suppress below k
    NoiseSource.java                  interface over randomness
    LaplaceNoiseSource.java           inverse-CDF Laplace, takes a java.util.Random
    PrivacyPipeline.java              composes KAnonymity + noise into an artifact
    ReleaseArtifact.java              stored result: bytes, computed k, epsilon charged
    ReleaseArtifactStore.java         interface
    InMemoryReleaseArtifactStore.java default implementation
    BudgetAccountant.java             interface, atomic reserve-or-refuse
    InMemoryBudgetAccountant.java     AtomicReference compare-and-set implementation
    KAnonymityFunction.java           ODRL constraint function
    EpsilonBudgetFunction.java        ODRL constraint function
    BenefitsAggregateDataSourceFactory.java   serves stored bytes
    BenefitsPrivacyExtension.java     registers the factory and the two functions
  src/main/resources/META-INF/services/org.eclipse.edc.spi.system.ServiceExtension
  src/test/java/org/benefits/policy/    one test class per unit above

connector/runtime/build.gradle.kts    MODIFIED: data plane modules
connector/runtime/provider.properties MODIFIED: data plane and CouchDB config
connector/runtime/consumer.properties MODIFIED: data plane config

packages/consent-odrl/src/compile.ts  MODIFIED: re-add the two constraints (Task 8)
packages/consent-odrl/test/compile.test.ts MODIFIED

tools/lib.sh                          MODIFIED: add a transfer step
tools/probe-transfer.sh               NEW: gate 0
tools/compile-policy.mjs              MODIFIED: pass kAnonymity and epsilon again
```

A separate Gradle module keeps the privacy pipeline out of `benefits-policy`, which is already 14 classes. The two modules communicate only through the constraint functions' shared namespace constants.

---

# Gate 0: prove a transfer can happen at all

No data has ever moved through this system. The runtime pulls `controlplane-base-bom` and `iam-mock` only, and the harness has no transfer step. Everything in this plan assumes a working data plane, so that assumption is tested first.

---

### Task 1: Stand up a data plane and move bytes end to end

**Files:**
- Modify: `connector/runtime/build.gradle.kts`
- Modify: `connector/runtime/provider.properties`, `connector/runtime/consumer.properties`
- Create: `connector/extensions/benefits-privacy/build.gradle.kts`
- Create: `connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/ProbeDataSourceFactory.java`
- Create: `connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/BenefitsPrivacyExtension.java`
- Create: `connector/extensions/benefits-privacy/src/main/resources/META-INF/services/org.eclipse.edc.spi.system.ServiceExtension`
- Modify: `settings.gradle.kts`
- Create: `tools/probe-transfer.sh`

**Interfaces:**
- Consumes: the existing connector runtime and `tools/lib.sh`'s start, publish and negotiate helpers.
- Produces: a booting runtime with an embedded data plane, and `BenefitsPrivacyExtension` which later tasks add registrations to.

- [ ] **Step 1: Add the module to the build**

In `settings.gradle.kts`, add alongside the existing includes:

```kotlin
include(":connector:extensions:benefits-privacy")
```

Create `connector/extensions/benefits-privacy/build.gradle.kts`:

```kotlin
plugins {
    `java-library`
}

dependencies {
    api(libs.edc.control.plane.spi)
    api(libs.edc.policy.engine.spi)
    api(libs.edc.data.plane.spi)
    implementation(libs.edc.control.plane.core)
    implementation(project(":connector:extensions:benefits-policy"))
}
```

Add to `gradle/libs.versions.toml` under `[libraries]`:

```toml
edc-data-plane-spi = { module = "org.eclipse.edc:data-plane-spi", version.ref = "edc" }
edc-data-plane-core = { module = "org.eclipse.edc:data-plane-core", version.ref = "edc" }
edc-data-plane-http = { module = "org.eclipse.edc:data-plane-http", version.ref = "edc" }
edc-data-plane-iam = { module = "org.eclipse.edc:data-plane-iam", version.ref = "edc" }
edc-data-plane-self-registration = { module = "org.eclipse.edc:data-plane-self-registration", version.ref = "edc" }
edc-data-plane-signaling-api = { module = "org.eclipse.edc:data-plane-signaling-api", version.ref = "edc" }
edc-data-plane-signaling-client = { module = "org.eclipse.edc:data-plane-signaling-client", version.ref = "edc" }
edc-transfer-data-plane-signaling = { module = "org.eclipse.edc:transfer-data-plane-signaling", version.ref = "edc" }
edc-control-plane-api-client = { module = "org.eclipse.edc:control-plane-api-client", version.ref = "edc" }
edc-validator-data-address-http-data = { module = "org.eclipse.edc:validator-data-address-http-data", version.ref = "edc" }
```

- [ ] **Step 2: Give the runtime a data plane**

Replace the `dependencies` block in `connector/runtime/build.gradle.kts` with this. Note the two exclusions: the EDC samples do exactly this, because the controlplane BOM ships signaling modules that conflict with the embedded data plane.

```kotlin
dependencies {
    runtimeOnly(libs.edc.bom.controlplane.base) {
        exclude(group = "org.eclipse.edc", module = "data-plane-signaling-core")
        exclude(group = "org.eclipse.edc", module = "data-plane-signaling-oauth2")
    }
    implementation(libs.edc.iam.mock)
    implementation(libs.edc.control.plane.api.client)
    implementation(libs.edc.transfer.data.plane.signaling)
    implementation(libs.edc.validator.data.address.http.data)
    implementation(libs.edc.data.plane.self.registration)
    implementation(libs.edc.data.plane.signaling.api)
    implementation(libs.edc.data.plane.signaling.client)
    implementation(libs.edc.data.plane.core)
    implementation(libs.edc.data.plane.http)
    implementation(libs.edc.data.plane.iam)

    implementation(project(":connector:extensions:benefits-policy"))
    implementation(project(":connector:extensions:benefits-privacy"))
}
```

- [ ] **Step 3: Add the data plane's own web context to both configs**

Append to `connector/runtime/provider.properties`:

```properties
edc.dataplane.api.public.baseurl=http://localhost:19291/public
web.http.public.port=19291
web.http.public.path=/public
```

Append to `connector/runtime/consumer.properties`:

```properties
edc.dataplane.api.public.baseurl=http://localhost:29291/public
web.http.public.port=29291
web.http.public.path=/public
```

- [ ] **Step 4: Write the probe data source factory**

`ProbeDataSourceFactory.java`. It serves a fixed marker so the probe can prove bytes actually arrived.

```java
package org.benefits.policy;

import org.eclipse.edc.connector.dataplane.spi.pipeline.DataSource;
import org.eclipse.edc.connector.dataplane.spi.pipeline.DataSourceFactory;
import org.eclipse.edc.connector.dataplane.spi.pipeline.InputStreamDataSource;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.types.domain.transfer.DataFlowStartMessage;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public class ProbeDataSourceFactory implements DataSourceFactory {

    public static final String TYPE = "BenefitsProbe";
    public static final String MARKER = "BENEFITS_TRANSFER_OK";

    @Override
    public String supportedType() {
        return TYPE;
    }

    @Override
    public DataSource createSource(DataFlowStartMessage request) {
        var body = MARKER + " asset=" + request.getAssetId();
        return new InputStreamDataSource("probe",
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public @NotNull Result<Void> validateRequest(DataFlowStartMessage request) {
        return Result.success();
    }
}
```

- [ ] **Step 5: Write the extension that registers it**

`BenefitsPrivacyExtension.java`:

```java
package org.benefits.policy;

import org.eclipse.edc.connector.dataplane.spi.pipeline.PipelineService;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

public class BenefitsPrivacyExtension implements ServiceExtension {

    @Inject
    private PipelineService pipelineService;

    @Override
    public String name() {
        return "BENEFITS privacy pipeline";
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        pipelineService.registerFactory(new ProbeDataSourceFactory());
        context.getMonitor().info("BENEFITS privacy pipeline registered");
    }
}
```

Write `src/main/resources/META-INF/services/org.eclipse.edc.spi.system.ServiceExtension`:

```
org.benefits.policy.BenefitsPrivacyExtension
```

- [ ] **Step 6: Build and confirm the runtime still boots**

Run: `./gradlew shadowJar`
Expected: BUILD SUCCESSFUL.

Then:

```bash
java -Dedc.fs.config=connector/runtime/provider.properties \
     -jar connector/runtime/build/libs/connector.jar > /tmp/probe-boot.log 2>&1 &
echo $! > /tmp/probe.pid
for _ in $(seq 1 60); do grep -q "Runtime .* ready" /tmp/probe-boot.log && break; sleep 1; done
grep -E "BENEFITS privacy pipeline registered|Runtime .* ready" /tmp/probe-boot.log
kill "$(cat /tmp/probe.pid)"
```

Expected: both lines present. If the runtime fails to boot, the dependency set is wrong; report the actual error rather than guessing at exclusions.

- [ ] **Step 7: Write the transfer probe script**

`tools/probe-transfer.sh`. It reuses `tools/lib.sh` for start, publish and negotiate, then adds the transfer step the harness has never had.

```bash
#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib.sh"

WORK=/tmp/benefits-probe-transfer
rm -rf "$WORK" && mkdir -p "$WORK"
SINK_LOG="$WORK/sink.log"

# A trivial sink: the consumer pushes to this, and we read what arrived.
python3 -m http.server 4000 --directory "$WORK" >/dev/null 2>&1 &
echo $! > "$WORK/sink.pid"
trap 'kill "$(cat "$WORK/sink.pid")" 2>/dev/null || true; stop_all' EXIT

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

stop_all
start_connector provider "$ROOT/connector/runtime/provider.properties" 19193
start_connector consumer "$ROOT/connector/runtime/consumer.properties" 29193

curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/assets" -d '{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@id": "benefits-probe-asset",
  "properties": { "name": "probe" },
  "dataAddress": { "type": "BenefitsProbe" }
}' >/dev/null
curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/policydefinitions" -d @"$WORK/policy.json" >/dev/null
curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/contractdefinitions" -d '{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@id": "benefits-probe-definition",
  "accessPolicyId": "benefits-policy",
  "contractPolicyId": "benefits-policy",
  "assetsSelector": []
}' >/dev/null

STATE=$(negotiate)
echo "negotiation: $STATE"
[ "$STATE" = "FINALIZED" ] || { echo "negotiation did not finalize" >&2; exit 1; }

AGREEMENT=$(curl -sf -H "$KEY" "$CONSUMER_MGMT/v3/contractnegotiations/$(cat /tmp/negotiation-id.txt)" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["contractAgreementId"])')
echo "agreement: $AGREEMENT"

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
  case "$TSTATE" in COMPLETED|STARTED|TERMINATED) break ;; esac
  sleep 1
done
echo "transfer state: $TSTATE"
grep -rl BENEFITS_TRANSFER_OK "$WORK" 2>/dev/null && echo "MARKER FOUND" || echo "MARKER NOT FOUND"
```

```bash
chmod +x tools/probe-transfer.sh
```

Note: `negotiate()` in `lib.sh` must write the negotiation id to `/tmp/negotiation-id.txt`. Add that line to `lib.sh` if it is not already there.

- [ ] **Step 8: Run the probe. THIS IS THE GATE.**

Run: `./gradlew shadowJar && ./tools/probe-transfer.sh`
Expected: negotiation FINALIZED, transfer state COMPLETED or STARTED, and `MARKER FOUND`.

If the marker never arrives, the data plane is not wired correctly. Read `/tmp/provider.log` for data plane registration lines and report exactly what happened. **Do not proceed to Task 2.** If this gate cannot be passed within a day of genuine effort, stop and report: the spec records a pre-release service as the fallback, and that decision belongs to the controller, not to a workaround.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat: embed a data plane and prove a transfer moves bytes"
```

---

# Mechanism: computed without any EDC involvement

Tasks 2 to 6 are pure Java with no connector, no CouchDB and no network. They are fast, deterministic, and where the privacy mathematics is actually verified.

---

### Task 2: Contribution model and the source seam

**Files:**
- Create: `.../Contribution.java`, `.../ContributionSource.java`, `.../InMemoryContributionSource.java`, `.../QuerySpec.java`
- Create: `.../src/test/java/org/benefits/policy/InMemoryContributionSourceTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `ContributionSource.contributionsFor(String siteId): List<Contribution>` and the `Contribution` and `QuerySpec` records that every later task uses.

- [ ] **Step 1: Write the failing test**

```java
package org.benefits.policy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryContributionSourceTest {

    private Contribution c(String participant, String site, String channel) {
        return new Contribution(participant, site, Map.of("channel", channel));
    }

    @Test
    void returnsOnlyContributionsForTheRequestedSite() {
        var source = new InMemoryContributionSource(List.of(
                c("p-1", "athens", "app"),
                c("p-2", "mytilene", "app")));
        assertThat(source.contributionsFor("athens")).hasSize(1);
        assertThat(source.contributionsFor("athens").get(0).participantRef()).isEqualTo("p-1");
    }

    @Test
    void returnsEmptyForAnUnknownSite() {
        var source = new InMemoryContributionSource(List.of(c("p-1", "athens", "app")));
        assertThat(source.contributionsFor("nowhere")).isEmpty();
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :connector:extensions:benefits-privacy:test`
Expected: compilation failure, the types do not exist.

- [ ] **Step 3: Write the model and the seam**

`Contribution.java`:

```java
package org.benefits.policy;

import java.util.Map;

/**
 * One contributed record as the privacy pipeline sees it. The attributes map holds
 * the quasi-identifier values a query may group by, such as channel or service type.
 */
public record Contribution(String participantRef, String siteId, Map<String, String> attributes) {

    public Contribution {
        attributes = Map.copyOf(attributes);
    }

    public String attribute(String name) {
        return attributes.getOrDefault(name, "");
    }
}
```

`ContributionSource.java`:

```java
package org.benefits.policy;

import java.util.List;

/**
 * Supplies the raw contributions a query aggregates over.
 * <p>
 * This interface exists so that every k-anonymity and differential privacy test runs
 * without a database. Only the CouchDB-backed implementation needs an integration test,
 * and it verifies the reader rather than the mathematics.
 */
public interface ContributionSource {

    List<Contribution> contributionsFor(String siteId);
}
```

`InMemoryContributionSource.java`:

```java
package org.benefits.policy;

import java.util.List;

public class InMemoryContributionSource implements ContributionSource {

    private final List<Contribution> contributions;

    public InMemoryContributionSource(List<Contribution> contributions) {
        this.contributions = List.copyOf(contributions);
    }

    @Override
    public List<Contribution> contributionsFor(String siteId) {
        return contributions.stream().filter(c -> c.siteId().equals(siteId)).toList();
    }
}
```

`QuerySpec.java`:

```java
package org.benefits.policy;

import java.util.List;

/**
 * A grouped count query. Means and sums are deliberately not supported: their
 * sensitivity depends on the value range, and an incorrect range silently
 * under-noises the result.
 */
public record QuerySpec(String siteId, List<String> groupBy) {

    public QuerySpec {
        groupBy = List.copyOf(groupBy);
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :connector:extensions:benefits-privacy:test`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: contribution model and the source seam"
```

---

### Task 3: k-anonymity

**Files:**
- Create: `.../GroupedCount.java`, `.../KAnonymity.java`
- Create: `.../src/test/java/org/benefits/policy/KAnonymityTest.java`

**Interfaces:**
- Consumes: `Contribution`, `QuerySpec` from Task 2.
- Produces: `KAnonymity.aggregate(List<Contribution>, QuerySpec, int k): List<GroupedCount>` and `GroupedCount(String groupKey, int count)`.

- [ ] **Step 1: Write the failing test**

```java
package org.benefits.policy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class KAnonymityTest {

    private Contribution c(String participant, String channel) {
        return new Contribution(participant, "athens", Map.of("channel", channel));
    }

    private List<Contribution> many(String channel, int n, String prefix) {
        return IntStream.range(0, n).mapToObj(i -> c(prefix + i, channel)).toList();
    }

    private final QuerySpec byChannel = new QuerySpec("athens", List.of("channel"));

    @Test
    void suppressesAGroupBelowK() {
        var result = KAnonymity.aggregate(many("app", 4, "a"), byChannel, 5);
        assertThat(result).isEmpty();
    }

    @Test
    void keepsAGroupExactlyAtK() {
        var result = KAnonymity.aggregate(many("app", 5, "a"), byChannel, 5);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).count()).isEqualTo(5);
    }

    @Test
    void suppressesOnlyTheSmallGroup() {
        var mixed = new java.util.ArrayList<Contribution>(many("app", 6, "a"));
        mixed.addAll(many("sms", 2, "s"));
        var result = KAnonymity.aggregate(mixed, byChannel, 5);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).groupKey()).isEqualTo("app");
    }

    @Test
    void countsDistinctParticipantsNotRows() {
        // one participant contributing three rows must count once, or the
        // sensitivity-of-1 assumption the noise calibration rests on is false
        var repeated = List.of(c("p-1", "app"), c("p-1", "app"), c("p-1", "app"),
                c("p-2", "app"), c("p-3", "app"), c("p-4", "app"), c("p-5", "app"));
        var result = KAnonymity.aggregate(repeated, byChannel, 5);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).count()).isEqualTo(5);
    }

    @Test
    void groupsBySeveralAttributesInOrder() {
        var spec = new QuerySpec("athens", List.of("channel", "service"));
        var rows = IntStream.range(0, 5)
                .mapToObj(i -> new Contribution("p" + i, "athens",
                        Map.of("channel", "app", "service", "housing")))
                .toList();
        var result = KAnonymity.aggregate(rows, spec, 5);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).groupKey()).isEqualTo("app|housing");
    }

    @Test
    void anEmptyGroupByProducesOneTotalGroup() {
        var spec = new QuerySpec("athens", List.of());
        var result = KAnonymity.aggregate(many("app", 7, "a"), spec, 5);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).groupKey()).isEmpty();
        assertThat(result.get(0).count()).isEqualTo(7);
    }

    @Test
    void resultIsSortedByGroupKeyForDeterminism() {
        var mixed = new java.util.ArrayList<Contribution>(many("zeta", 5, "z"));
        mixed.addAll(many("alpha", 5, "a"));
        var result = KAnonymity.aggregate(mixed, byChannel, 5);
        assertThat(result).extracting(GroupedCount::groupKey).containsExactly("alpha", "zeta");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :connector:extensions:benefits-privacy:test --tests '*KAnonymityTest*'`
Expected: compilation failure.

- [ ] **Step 3: Implement**

`GroupedCount.java`:

```java
package org.benefits.policy;

public record GroupedCount(String groupKey, int count) {
}
```

`KAnonymity.java`:

```java
package org.benefits.policy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Groups contributions and suppresses groups whose distinct-participant count is
 * below k.
 * <p>
 * Two properties matter and are tested. Counting is by DISTINCT participant, not by
 * row: the sensitivity-of-1 calibration the Laplace noise depends on is only correct
 * if one participant can move a group's count by at most one. And the output is
 * sorted by group key, so a stored artifact is byte-reproducible.
 * <p>
 * Suppression runs on TRUE counts. Which groups survive is therefore not itself
 * differentially private; only the released values are. This is a known and
 * deliberate limitation, recorded in the design spec.
 */
public final class KAnonymity {

    private static final String KEY_SEPARATOR = "|";

    private KAnonymity() {
    }

    public static List<GroupedCount> aggregate(List<Contribution> contributions, QuerySpec query, int k) {
        Map<String, Set<String>> participantsByGroup = new HashMap<>();

        for (var contribution : contributions) {
            var key = groupKey(contribution, query);
            participantsByGroup.computeIfAbsent(key, unused -> new HashSet<>())
                    .add(contribution.participantRef());
        }

        List<GroupedCount> surviving = new ArrayList<>();
        for (var entry : participantsByGroup.entrySet()) {
            var distinct = entry.getValue().size();
            if (distinct >= k) {
                surviving.add(new GroupedCount(entry.getKey(), distinct));
            }
        }

        surviving.sort(Comparator.comparing(GroupedCount::groupKey));
        return List.copyOf(surviving);
    }

    private static String groupKey(Contribution contribution, QuerySpec query) {
        return String.join(KEY_SEPARATOR,
                query.groupBy().stream().map(contribution::attribute).toList());
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :connector:extensions:benefits-privacy:test --tests '*KAnonymityTest*'`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: k-anonymity aggregation counting distinct participants"
```

---

### Task 4: Laplace noise with an injectable RNG

**Files:**
- Create: `.../NoiseSource.java`, `.../LaplaceNoiseSource.java`
- Create: `.../src/test/java/org/benefits/policy/LaplaceNoiseSourceTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `NoiseSource.noise(double epsilon): double` and `new LaplaceNoiseSource(java.util.Random)`.

- [ ] **Step 1: Write the failing test**

```java
package org.benefits.policy;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LaplaceNoiseSourceTest {

    @Test
    void isReproducibleFromASeed() {
        var a = new LaplaceNoiseSource(new Random(42)).noise(0.5);
        var b = new LaplaceNoiseSource(new Random(42)).noise(0.5);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void differentSeedsGiveDifferentNoise() {
        var a = new LaplaceNoiseSource(new Random(1)).noise(0.5);
        var b = new LaplaceNoiseSource(new Random(2)).noise(0.5);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void smallerEpsilonGivesLargerSpread() {
        // scale is 1/epsilon, so a tighter privacy budget must add more noise
        var tight = spread(new LaplaceNoiseSource(new Random(7)), 0.1);
        var loose = spread(new LaplaceNoiseSource(new Random(7)), 2.0);
        assertThat(tight).isGreaterThan(loose);
    }

    @Test
    void isCentredNearZeroOverManyDraws() {
        var source = new LaplaceNoiseSource(new Random(11));
        double total = 0;
        for (var i = 0; i < 20000; i++) {
            total += source.noise(1.0);
        }
        assertThat(Math.abs(total / 20000)).isLessThan(0.1);
    }

    @Test
    void rejectsANonPositiveEpsilon() {
        var source = new LaplaceNoiseSource(new Random(1));
        assertThatThrownBy(() -> source.noise(0.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> source.noise(-1.0)).isInstanceOf(IllegalArgumentException.class);
    }

    private double spread(NoiseSource source, double epsilon) {
        double sumSquares = 0;
        for (var i = 0; i < 5000; i++) {
            var n = source.noise(epsilon);
            sumSquares += n * n;
        }
        return Math.sqrt(sumSquares / 5000);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :connector:extensions:benefits-privacy:test --tests '*LaplaceNoiseSourceTest*'`
Expected: compilation failure.

- [ ] **Step 3: Implement**

`NoiseSource.java`:

```java
package org.benefits.policy;

/**
 * Supplies calibrated noise. Injectable so tests can seed it and get reproducible
 * output: a differential privacy implementation that cannot be tested
 * deterministically cannot be verified at all.
 */
public interface NoiseSource {

    double noise(double epsilon);
}
```

`LaplaceNoiseSource.java`:

```java
package org.benefits.policy;

import java.util.Random;

/**
 * Laplace noise by inverse transform sampling, at scale 1/epsilon.
 * <p>
 * Scale is 1/epsilon rather than sensitivity/epsilon because this pipeline only
 * supports grouped counts over deduplicated participants, where the L1 sensitivity
 * is exactly 1. That condition is enforced in KAnonymity, not assumed here.
 * <p>
 * Floating point side channels in this mechanism are out of scope for this
 * reference implementation, per the design spec.
 */
public class LaplaceNoiseSource implements NoiseSource {

    private final Random random;

    public LaplaceNoiseSource(Random random) {
        this.random = random;
    }

    @Override
    public double noise(double epsilon) {
        if (epsilon <= 0) {
            throw new IllegalArgumentException("epsilon must be positive, got: " + epsilon);
        }
        var scale = 1.0 / epsilon;
        var u = random.nextDouble() - 0.5;
        return -scale * Math.signum(u) * Math.log(1 - 2 * Math.abs(u));
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :connector:extensions:benefits-privacy:test --tests '*LaplaceNoiseSourceTest*'`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: Laplace noise source with an injectable RNG"
```

---

### Task 5: The budget accountant, atomic

**Files:**
- Create: `.../BudgetAccountant.java`, `.../InMemoryBudgetAccountant.java`
- Create: `.../src/test/java/org/benefits/policy/InMemoryBudgetAccountantTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `BudgetAccountant.reserve(String siteId, double epsilon): boolean` and `BudgetAccountant.remaining(String siteId): double`.

**Why this task is different from every other store in this project.** Ruling R30 let the steward authorization store replace wholesale under a documented sole-writer precondition, because a lost signature is a data-integrity problem. This store cannot take that deal: two concurrent requests each reading a remaining budget of 0.5, each concluding they can afford 0.5, and each proceeding, spends 1.0 against a budget of 0.5. That is a privacy guarantee silently violated with under-noised figures already released. There is therefore exactly one mutating operation and it is atomic.

- [ ] **Step 1: Write the failing test**

```java
package org.benefits.policy;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryBudgetAccountantTest {

    @Test
    void startsWithTheConfiguredTotal() {
        var accountant = new InMemoryBudgetAccountant(2.0);
        assertThat(accountant.remaining("athens")).isEqualTo(2.0);
    }

    @Test
    void reservesAndDecrements() {
        var accountant = new InMemoryBudgetAccountant(2.0);
        assertThat(accountant.reserve("athens", 0.5)).isTrue();
        assertThat(accountant.remaining("athens")).isEqualTo(1.5);
    }

    @Test
    void refusesWhenTheRemainingBudgetIsInsufficient() {
        var accountant = new InMemoryBudgetAccountant(1.0);
        assertThat(accountant.reserve("athens", 0.6)).isTrue();
        assertThat(accountant.reserve("athens", 0.6)).isFalse();
        assertThat(accountant.remaining("athens")).isEqualTo(0.4);
    }

    @Test
    void allowsAReservationExactlyExhaustingTheBudget() {
        var accountant = new InMemoryBudgetAccountant(1.0);
        assertThat(accountant.reserve("athens", 1.0)).isTrue();
        assertThat(accountant.remaining("athens")).isEqualTo(0.0);
        assertThat(accountant.reserve("athens", 0.0001)).isFalse();
    }

    @Test
    void tracksSitesIndependently() {
        var accountant = new InMemoryBudgetAccountant(1.0);
        assertThat(accountant.reserve("athens", 1.0)).isTrue();
        assertThat(accountant.reserve("mytilene", 1.0)).isTrue();
    }

    @Test
    void refusesANonPositiveReservation() {
        var accountant = new InMemoryBudgetAccountant(1.0);
        assertThat(accountant.reserve("athens", 0.0)).isFalse();
        assertThat(accountant.reserve("athens", -0.5)).isFalse();
        assertThat(accountant.remaining("athens")).isEqualTo(1.0);
    }

    @Test
    void concurrentReservationsNeverOverspend() throws Exception {
        // 2.0 total, 0.5 each, so exactly 4 of 50 racing threads may succeed.
        // A read-then-write accountant fails this; a compare-and-set one passes.
        var accountant = new InMemoryBudgetAccountant(2.0);
        var threads = 50;
        var succeeded = new AtomicInteger();
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        var pool = Executors.newFixedThreadPool(16);

        for (var i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (accountant.reserve("athens", 0.5)) {
                        succeeded.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(succeeded.get()).isEqualTo(4);
        assertThat(accountant.remaining("athens")).isEqualTo(0.0);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :connector:extensions:benefits-privacy:test --tests '*BudgetAccountant*'`
Expected: compilation failure.

- [ ] **Step 3: Implement**

`BudgetAccountant.java`:

```java
package org.benefits.policy;

/**
 * Tracks the differential privacy budget for each site.
 * <p>
 * There is deliberately no read-then-write API. {@link #reserve} is the only
 * mutating operation and it is atomic, because two callers each reading the same
 * remaining budget and each deciding they can afford it would overspend, releasing
 * under-noised figures with nothing in the system aware of it. That is a privacy
 * guarantee violated rather than data lost, so it cannot be handled by a documented
 * sole-writer precondition the way the steward authorization store is.
 */
public interface BudgetAccountant {

    /**
     * Atomically reserves epsilon for a site. Returns false, changing nothing, when
     * the remaining budget is insufficient or epsilon is not positive.
     */
    boolean reserve(String siteId, double epsilon);

    double remaining(String siteId);
}
```

`InMemoryBudgetAccountant.java`:

```java
package org.benefits.policy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory accountant. A restart resets every site's budget, which is a stated
 * limitation of this reference implementation and materially more consequential
 * than the equivalent limitation on the steward authorization store: a reset
 * silently restores spending capacity that was legitimately exhausted.
 */
public class InMemoryBudgetAccountant implements BudgetAccountant {

    private final double total;
    private final Map<String, AtomicReference<Double>> remaining = new ConcurrentHashMap<>();

    public InMemoryBudgetAccountant(double total) {
        this.total = total;
    }

    @Override
    public boolean reserve(String siteId, double epsilon) {
        if (epsilon <= 0) {
            return false;
        }
        var cell = cellFor(siteId);
        while (true) {
            var current = cell.get();
            if (current < epsilon) {
                return false;
            }
            var next = current - epsilon;
            if (cell.compareAndSet(current, next)) {
                return true;
            }
        }
    }

    @Override
    public double remaining(String siteId) {
        return cellFor(siteId).get();
    }

    private AtomicReference<Double> cellFor(String siteId) {
        return remaining.computeIfAbsent(siteId, unused -> new AtomicReference<>(total));
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :connector:extensions:benefits-privacy:test --tests '*BudgetAccountant*'`
Expected: PASS, 7 tests including the concurrency case.

- [ ] **Step 5: Prove the concurrency test discriminates**

Temporarily replace the `reserve` body with a naive read-then-write:

```java
        var cell = cellFor(siteId);
        if (cell.get() < epsilon) { return false; }
        cell.set(cell.get() - epsilon);
        return true;
```

Run the concurrency test. Expected: it FAILS, with more than 4 successes. Then restore the compare-and-set version and confirm it passes again. Record both outputs in your report. A concurrency test that passes against a broken implementation proves nothing.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: atomic privacy budget accountant"
```

---

### Task 6: Compose the pipeline into a stored artifact

**Files:**
- Create: `.../ReleaseArtifact.java`, `.../PrivacyPipeline.java`
- Create: `.../src/test/java/org/benefits/policy/PrivacyPipelineTest.java`

**Interfaces:**
- Consumes: `KAnonymity`, `NoiseSource`, `BudgetAccountant`, `ContributionSource`, `QuerySpec` from Tasks 2 to 5.
- Produces: `PrivacyPipeline.compute(String releaseRequestId, QuerySpec, int k, double epsilon): Optional<ReleaseArtifact>` and the `ReleaseArtifact` record with `releaseRequestId()`, `bytes()`, `computedK()`, `epsilonCharged()`.

- [ ] **Step 1: Write the failing test**

```java
package org.benefits.policy;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class PrivacyPipelineTest {

    private List<Contribution> participants(String channel, int n, String prefix) {
        return IntStream.range(0, n)
                .mapToObj(i -> new Contribution(prefix + i, "athens", Map.of("channel", channel)))
                .toList();
    }

    private PrivacyPipeline pipeline(List<Contribution> rows, BudgetAccountant accountant, long seed) {
        return new PrivacyPipeline(new InMemoryContributionSource(rows),
                new LaplaceNoiseSource(new Random(seed)), accountant);
    }

    private final QuerySpec byChannel = new QuerySpec("athens", List.of("channel"));

    @Test
    void producesAnArtifactWhenBudgetAllows() {
        var pipeline = pipeline(participants("app", 6, "a"), new InMemoryBudgetAccountant(2.0), 42);
        var artifact = pipeline.compute("rr-1", byChannel, 5, 0.5);
        assertThat(artifact).isPresent();
        assertThat(artifact.get().releaseRequestId()).isEqualTo("rr-1");
        assertThat(artifact.get().epsilonCharged()).isEqualTo(0.5);
    }

    @Test
    void refusesWhenTheBudgetIsExhausted() {
        var accountant = new InMemoryBudgetAccountant(0.4);
        var pipeline = pipeline(participants("app", 6, "a"), accountant, 42);
        assertThat(pipeline.compute("rr-1", byChannel, 5, 0.5)).isEmpty();
        assertThat(accountant.remaining("athens")).isEqualTo(0.4);
    }

    @Test
    void doesNotChargeBudgetWhenItRefuses() {
        var accountant = new InMemoryBudgetAccountant(0.4);
        pipeline(participants("app", 6, "a"), accountant, 42).compute("rr-1", byChannel, 5, 0.5);
        assertThat(accountant.remaining("athens")).isEqualTo(0.4);
    }

    @Test
    void reportsComputedKAsTheSmallestSurvivingGroup() {
        var rows = new ArrayList<Contribution>(participants("app", 9, "a"));
        rows.addAll(participants("sms", 6, "s"));
        var artifact = pipeline(rows, new InMemoryBudgetAccountant(2.0), 42)
                .compute("rr-1", byChannel, 5, 0.5);
        assertThat(artifact).isPresent();
        assertThat(artifact.get().computedK()).isEqualTo(6);
    }

    @Test
    void reportsComputedKAsZeroWhenEverythingIsSuppressed() {
        var artifact = pipeline(participants("app", 3, "a"), new InMemoryBudgetAccountant(2.0), 42)
                .compute("rr-1", byChannel, 5, 0.5);
        assertThat(artifact).isPresent();
        assertThat(artifact.get().computedK()).isZero();
    }

    @Test
    void isDeterministicForAGivenSeed() {
        var a = pipeline(participants("app", 6, "a"), new InMemoryBudgetAccountant(2.0), 99)
                .compute("rr-1", byChannel, 5, 0.5).orElseThrow();
        var b = pipeline(participants("app", 6, "a"), new InMemoryBudgetAccountant(2.0), 99)
                .compute("rr-1", byChannel, 5, 0.5).orElseThrow();
        assertThat(a.bytes()).isEqualTo(b.bytes());
    }

    @Test
    void addsNoiseSoTheOutputIsNotTheTrueCount() {
        var artifact = pipeline(participants("app", 6, "a"), new InMemoryBudgetAccountant(2.0), 42)
                .compute("rr-1", byChannel, 5, 0.5).orElseThrow();
        var text = new String(artifact.bytes(), StandardCharsets.UTF_8);
        assertThat(text).contains("app");
        // the noised value is a double, not the bare integer 6
        assertThat(text).doesNotContain("\"count\":6,");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :connector:extensions:benefits-privacy:test --tests '*PrivacyPipelineTest*'`
Expected: compilation failure.

- [ ] **Step 3: Implement**

`ReleaseArtifact.java`:

```java
package org.benefits.policy;

/**
 * An anonymised aggregate, computed once and stored.
 * <p>
 * The bytes are served unchanged on every transfer. Recomputing per transfer would
 * let a requester re-run the same transfer repeatedly and average the results to
 * recover the true value, while the budget accountant charged epsilon only once.
 */
public record ReleaseArtifact(String releaseRequestId, byte[] bytes, int computedK, double epsilonCharged) {

    public ReleaseArtifact {
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
```

`PrivacyPipeline.java`:

```java
package org.benefits.policy;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Composes k-anonymity and Laplace noise into a stored artifact, reserving privacy
 * budget before doing any work that would be released.
 */
public class PrivacyPipeline {

    private final ContributionSource contributions;
    private final NoiseSource noise;
    private final BudgetAccountant accountant;

    public PrivacyPipeline(ContributionSource contributions, NoiseSource noise, BudgetAccountant accountant) {
        this.contributions = contributions;
        this.noise = noise;
        this.accountant = accountant;
    }

    /**
     * Returns empty when the site's remaining privacy budget cannot cover epsilon.
     * Nothing is charged in that case.
     */
    public Optional<ReleaseArtifact> compute(String releaseRequestId, QuerySpec query, int k, double epsilon) {
        if (!accountant.reserve(query.siteId(), epsilon)) {
            return Optional.empty();
        }

        var groups = KAnonymity.aggregate(contributions.contributionsFor(query.siteId()), query, k);

        var computedK = groups.stream().mapToInt(GroupedCount::count).min().orElse(0);

        var json = new StringJoiner(",", "{\"groups\":[", "]}");
        for (var group : groups) {
            var noised = group.count() + noise.noise(epsilon);
            json.add("{\"group\":\"" + group.groupKey() + "\",\"count\":" + noised + "}");
        }

        return Optional.of(new ReleaseArtifact(releaseRequestId,
                json.toString().getBytes(StandardCharsets.UTF_8), computedK, epsilon));
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :connector:extensions:benefits-privacy:test --tests '*PrivacyPipelineTest*'`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: compose k-anonymity and noise into a stored release artifact"
```

---

### Task 7: Artifact store

**Files:**
- Create: `.../ReleaseArtifactStore.java`, `.../InMemoryReleaseArtifactStore.java`
- Create: `.../src/test/java/org/benefits/policy/InMemoryReleaseArtifactStoreTest.java`

**Interfaces:**
- Consumes: `ReleaseArtifact` from Task 6.
- Produces: `ReleaseArtifactStore.findByReleaseRequestId(String): ReleaseArtifact` returning null when absent, and `save(ReleaseArtifact)`.

- [ ] **Step 1: Write the failing test**

```java
package org.benefits.policy;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryReleaseArtifactStoreTest {

    private final ReleaseArtifactStore store = new InMemoryReleaseArtifactStore();

    private ReleaseArtifact artifact(String id) {
        return new ReleaseArtifact(id, "{}".getBytes(StandardCharsets.UTF_8), 6, 0.5);
    }

    @Test
    void returnsNullWhenAbsent() {
        assertThat(store.findByReleaseRequestId("missing")).isNull();
    }

    @Test
    void roundTripsAnArtifact() {
        store.save(artifact("rr-1"));
        var found = store.findByReleaseRequestId("rr-1");
        assertThat(found).isNotNull();
        assertThat(found.computedK()).isEqualTo(6);
        assertThat(found.epsilonCharged()).isEqualTo(0.5);
    }

    @Test
    void servesIdenticalBytesOnEveryRead() {
        store.save(artifact("rr-1"));
        assertThat(store.findByReleaseRequestId("rr-1").bytes())
                .isEqualTo(store.findByReleaseRequestId("rr-1").bytes());
    }

    @Test
    void mutatingReturnedBytesDoesNotChangeTheStoredArtifact() {
        store.save(artifact("rr-1"));
        var first = store.findByReleaseRequestId("rr-1").bytes();
        first[0] = (byte) 'X';
        assertThat(store.findByReleaseRequestId("rr-1").bytes()[0]).isEqualTo((byte) '{');
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :connector:extensions:benefits-privacy:test --tests '*ReleaseArtifactStore*'`
Expected: compilation failure.

- [ ] **Step 3: Implement**

`ReleaseArtifactStore.java`:

```java
package org.benefits.policy;

import org.jetbrains.annotations.Nullable;

/**
 * Holds computed anonymised artifacts, keyed by release request id.
 * <p>
 * Like the steward authorization store, save REPLACES any existing artifact for the
 * same release request id, and the caller must be the sole writer for a given id.
 * Unlike the budget accountant, that is acceptable here: replacing an artifact loses
 * data rather than violating a privacy guarantee, because the budget was already
 * charged when the artifact was computed.
 */
public interface ReleaseArtifactStore {

    @Nullable
    ReleaseArtifact findByReleaseRequestId(String releaseRequestId);

    void save(ReleaseArtifact artifact);
}
```

`InMemoryReleaseArtifactStore.java`:

```java
package org.benefits.policy;

import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryReleaseArtifactStore implements ReleaseArtifactStore {

    private final Map<String, ReleaseArtifact> byReleaseRequest = new ConcurrentHashMap<>();

    @Override
    @Nullable
    public ReleaseArtifact findByReleaseRequestId(String releaseRequestId) {
        return byReleaseRequest.get(releaseRequestId);
    }

    @Override
    public void save(ReleaseArtifact artifact) {
        byReleaseRequest.put(artifact.releaseRequestId(), artifact);
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :connector:extensions:benefits-privacy:test --tests '*ReleaseArtifactStore*'`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: release artifact store"
```

---

# Enforcement: wire the artifact into negotiation and transfer

---

### Task 8: The two constraint functions, bindings, options and emission, as ONE change

**This task must not be split.** EDC rejects an entire policy at definition-creation time with HTTP 400 when a left operand has no registered function. Adding emission without functions, or functions without emission, breaks policy creation outright. That is exactly what happened in phase 2 and why these two constraints were removed.

**Files:**
- Create: `.../KAnonymityFunction.java`, `.../EpsilonBudgetFunction.java`
- Create: `.../src/test/java/org/benefits/policy/PrivacyConstraintFunctionsTest.java`
- Modify: `connector/extensions/benefits-policy/src/main/java/org/benefits/policy/BenefitsNamespace.java`
- Modify: `.../BenefitsPrivacyExtension.java`
- Modify: `packages/consent-odrl/src/compile.ts`, `packages/consent-odrl/test/compile.test.ts`
- Modify: `tools/compile-policy.mjs`

**Interfaces:**
- Consumes: `ReleaseArtifactStore` from Task 7, `Constraints.siblingLiteral` from the existing `benefits-policy` module.
- Produces: two registered constraint functions and a compiler that emits their constraints.

- [ ] **Step 1: Add the namespace keys**

In `connector/extensions/benefits-policy/src/main/java/org/benefits/policy/BenefitsNamespace.java`, add alongside the existing keys:

```java
    public static final String K_ANONYMITY_KEY = BENEFITS_NAMESPACE + "kAnonymity";
    public static final String EPSILON_BUDGET_KEY = BENEFITS_NAMESPACE + "epsilonBudget";
```

- [ ] **Step 2: Write the failing test**

```java
package org.benefits.policy;

import org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext;
import org.eclipse.edc.participant.spi.ParticipantAgent;
import org.eclipse.edc.policy.model.AtomicConstraint;
import org.eclipse.edc.policy.model.LiteralExpression;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.policy.model.Permission;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.benefits.policy.BenefitsNamespace.RELEASE_REQUEST_KEY;

class PrivacyConstraintFunctionsTest {

    private final ReleaseArtifactStore store = new InMemoryReleaseArtifactStore();

    private ContractNegotiationPolicyContext context() {
        return new ContractNegotiationPolicyContext(
                new ParticipantAgent("consumer", Map.of(), Map.of()));
    }

    private Permission ruleFor(String releaseRequestId) {
        return Permission.Builder.newInstance()
                .constraint(AtomicConstraint.Builder.newInstance()
                        .leftExpression(new LiteralExpression(RELEASE_REQUEST_KEY))
                        .operator(Operator.EQ)
                        .rightExpression(new LiteralExpression(releaseRequestId))
                        .build())
                .build();
    }

    private void store(String id, int computedK, double epsilon) {
        store.save(new ReleaseArtifact(id, "{}".getBytes(StandardCharsets.UTF_8), computedK, epsilon));
    }

    @Test
    void kAnonymityAllowsWhenComputedKMeetsTheThreshold() {
        store("rr-1", 6, 0.5);
        var fn = new KAnonymityFunction(store);
        assertThat(fn.evaluate(Operator.GEQ, "5", ruleFor("rr-1"), context())).isTrue();
    }

    @Test
    void kAnonymityAllowsAtExactlyTheThreshold() {
        store("rr-2", 5, 0.5);
        var fn = new KAnonymityFunction(store);
        assertThat(fn.evaluate(Operator.GEQ, "5", ruleFor("rr-2"), context())).isTrue();
    }

    @Test
    void kAnonymityRefusesBelowTheThreshold() {
        store("rr-3", 4, 0.5);
        var fn = new KAnonymityFunction(store);
        assertThat(fn.evaluate(Operator.GEQ, "5", ruleFor("rr-3"), context())).isFalse();
    }

    @Test
    void kAnonymityRefusesWhenTheArtifactIsMissing() {
        var ctx = context();
        var fn = new KAnonymityFunction(store);
        assertThat(fn.evaluate(Operator.GEQ, "5", ruleFor("rr-absent"), ctx)).isFalse();
        assertThat(ctx.getProblems()).isNotEmpty();
    }

    @Test
    void kAnonymityRefusesAndReportsAnUnsupportedOperator() {
        store("rr-4", 6, 0.5);
        var ctx = context();
        var fn = new KAnonymityFunction(store);
        assertThat(fn.evaluate(Operator.EQ, "5", ruleFor("rr-4"), ctx)).isFalse();
        assertThat(ctx.getProblems()).isNotEmpty();
    }

    @Test
    void kAnonymityRefusesANonNumericRightOperand() {
        store("rr-5", 6, 0.5);
        var ctx = context();
        var fn = new KAnonymityFunction(store);
        assertThat(fn.evaluate(Operator.GEQ, "five", ruleFor("rr-5"), ctx)).isFalse();
        assertThat(ctx.getProblems()).isNotEmpty();
    }

    @Test
    void epsilonAllowsWhenChargedIsWithinTheCap() {
        store("rr-6", 6, 0.5);
        var fn = new EpsilonBudgetFunction(store);
        assertThat(fn.evaluate(Operator.LEQ, "0.5", ruleFor("rr-6"), context())).isTrue();
    }

    @Test
    void epsilonRefusesWhenChargedExceedsTheCap() {
        store("rr-7", 6, 0.9);
        var fn = new EpsilonBudgetFunction(store);
        assertThat(fn.evaluate(Operator.LEQ, "0.5", ruleFor("rr-7"), context())).isFalse();
    }

    @Test
    void epsilonRefusesWhenTheArtifactIsMissing() {
        var ctx = context();
        var fn = new EpsilonBudgetFunction(store);
        assertThat(fn.evaluate(Operator.LEQ, "0.5", ruleFor("rr-absent"), ctx)).isFalse();
        assertThat(ctx.getProblems()).isNotEmpty();
    }

    @Test
    void bothRefuseWhenTheReleaseRequestSiblingIsMissing() {
        var bare = Permission.Builder.newInstance().build();
        var kctx = context();
        var ectx = context();
        assertThat(new KAnonymityFunction(store).evaluate(Operator.GEQ, "5", bare, kctx)).isFalse();
        assertThat(new EpsilonBudgetFunction(store).evaluate(Operator.LEQ, "0.5", bare, ectx)).isFalse();
        assertThat(kctx.getProblems()).isNotEmpty();
        assertThat(ectx.getProblems()).isNotEmpty();
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./gradlew :connector:extensions:benefits-privacy:test --tests '*PrivacyConstraintFunctionsTest*'`
Expected: compilation failure.

- [ ] **Step 4: Implement both functions**

`KAnonymityFunction.java`:

```java
package org.benefits.policy;

import org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext;
import org.eclipse.edc.policy.engine.spi.AtomicConstraintRuleFunction;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.policy.model.Permission;

import static org.benefits.policy.BenefitsNamespace.RELEASE_REQUEST_KEY;

/**
 * Refuses a release whose stored artifact does not meet the required k.
 * <p>
 * The artifact is computed and stored when the release request is created, long
 * before this runs, because constraint functions evaluate during contract
 * negotiation and no transfer exists yet.
 */
public class KAnonymityFunction implements AtomicConstraintRuleFunction<Permission, ContractNegotiationPolicyContext> {

    private final ReleaseArtifactStore store;

    public KAnonymityFunction(ReleaseArtifactStore store) {
        this.store = store;
    }

    @Override
    public boolean evaluate(Operator operator, Object rightValue, Permission rule, ContractNegotiationPolicyContext context) {
        if (operator != Operator.GEQ) {
            context.reportProblem("kAnonymity does not support operator: " + operator);
            return false;
        }

        Integer required = asInteger(rightValue);
        if (required == null) {
            context.reportProblem("kAnonymity right operand is not an integer: " + rightValue);
            return false;
        }

        var releaseRequestId = Constraints.siblingLiteral(rule, RELEASE_REQUEST_KEY);
        if (releaseRequestId == null) {
            context.reportProblem("kAnonymity requires a sibling " + RELEASE_REQUEST_KEY + " constraint");
            return false;
        }

        var artifact = store.findByReleaseRequestId(releaseRequestId);
        if (artifact == null) {
            context.reportProblem("no release artifact for release request " + releaseRequestId);
            return false;
        }

        return artifact.computedK() >= required;
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string) {
            try {
                return Integer.valueOf(string.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
```

`EpsilonBudgetFunction.java`:

```java
package org.benefits.policy;

import org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext;
import org.eclipse.edc.policy.engine.spi.AtomicConstraintRuleFunction;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.policy.model.Permission;

import static org.benefits.policy.BenefitsNamespace.RELEASE_REQUEST_KEY;

/**
 * Refuses a release whose stored artifact was charged more epsilon than the policy
 * permits.
 */
public class EpsilonBudgetFunction implements AtomicConstraintRuleFunction<Permission, ContractNegotiationPolicyContext> {

    private final ReleaseArtifactStore store;

    public EpsilonBudgetFunction(ReleaseArtifactStore store) {
        this.store = store;
    }

    @Override
    public boolean evaluate(Operator operator, Object rightValue, Permission rule, ContractNegotiationPolicyContext context) {
        if (operator != Operator.LEQ) {
            context.reportProblem("epsilonBudget does not support operator: " + operator);
            return false;
        }

        Double cap = asDouble(rightValue);
        if (cap == null) {
            context.reportProblem("epsilonBudget right operand is not a number: " + rightValue);
            return false;
        }

        var releaseRequestId = Constraints.siblingLiteral(rule, RELEASE_REQUEST_KEY);
        if (releaseRequestId == null) {
            context.reportProblem("epsilonBudget requires a sibling " + RELEASE_REQUEST_KEY + " constraint");
            return false;
        }

        var artifact = store.findByReleaseRequestId(releaseRequestId);
        if (artifact == null) {
            context.reportProblem("no release artifact for release request " + releaseRequestId);
            return false;
        }

        return artifact.epsilonCharged() <= cap;
    }

    private static Double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String string) {
            try {
                return Double.valueOf(string.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
```

- [ ] **Step 5: Register both, with their scope bindings**

Replace `BenefitsPrivacyExtension.java` with:

```java
package org.benefits.policy;

import org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext;
import org.eclipse.edc.connector.dataplane.spi.pipeline.PipelineService;
import org.eclipse.edc.policy.engine.spi.PolicyEngine;
import org.eclipse.edc.policy.engine.spi.RuleBindingRegistry;
import org.eclipse.edc.policy.model.Permission;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Provider;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

import static org.benefits.policy.BenefitsNamespace.EPSILON_BUDGET_KEY;
import static org.benefits.policy.BenefitsNamespace.K_ANONYMITY_KEY;
import static org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext.NEGOTIATION_SCOPE;

public class BenefitsPrivacyExtension implements ServiceExtension {

    @Inject
    private PipelineService pipelineService;
    @Inject
    private RuleBindingRegistry ruleBindingRegistry;
    @Inject
    private PolicyEngine policyEngine;

    private final ReleaseArtifactStore artifactStore = new InMemoryReleaseArtifactStore();

    @Override
    public String name() {
        return "BENEFITS privacy pipeline";
    }

    @Provider
    public ReleaseArtifactStore releaseArtifactStore() {
        return artifactStore;
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        pipelineService.registerFactory(new ProbeDataSourceFactory());

        ruleBindingRegistry.bind(K_ANONYMITY_KEY, NEGOTIATION_SCOPE);
        ruleBindingRegistry.bind(EPSILON_BUDGET_KEY, NEGOTIATION_SCOPE);
        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class,
                K_ANONYMITY_KEY, new KAnonymityFunction(artifactStore));
        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class,
                EPSILON_BUDGET_KEY, new EpsilonBudgetFunction(artifactStore));

        context.getMonitor().info("BENEFITS privacy pipeline registered");
    }
}
```

- [ ] **Step 6: Re-add emission to the compiler**

In `packages/consent-odrl/src/compile.ts`, add back to `CompileOptions`:

```typescript
  kAnonymity: number;
  epsilon: number;
```

and add these two constraints to the emitted permission, after `benefits:fieldSet`. Note both right operands are STRINGS: EDC's management API validator rejects numeric right operands with a ClassCastException.

```typescript
          constraint('benefits:kAnonymity', 'gteq', String(options.kAnonymity)),
          constraint('benefits:epsilonBudget', 'lteq', String(options.epsilon)),
```

Replace the comment left where they used to be with one recording that they are now emitted because their functions exist.

- [ ] **Step 7: Update the compiler tests**

In `packages/consent-odrl/test/compile.test.ts`, add `kAnonymity: 5, epsilon: 0.5` to every `compilePolicy` options object, and replace the test asserting the two constraints are absent with:

```typescript
  it('emits kAnonymity and epsilonBudget as strings, because EDC rejects numeric right operands', () => {
    expect(constraintFor(policy, 'benefits:kAnonymity')).toEqual({
      '@type': 'AtomicConstraint',
      leftOperand: 'benefits:kAnonymity',
      operator: 'gteq',
      rightOperand: '5'
    });
    expect(constraintFor(policy, 'benefits:epsilonBudget')).toEqual({
      '@type': 'AtomicConstraint',
      leftOperand: 'benefits:epsilonBudget',
      operator: 'lteq',
      rightOperand: '0.5'
    });
  });
```

In `tools/compile-policy.mjs`, add `kAnonymity: 5, epsilon: 0.5` back to the options object.

- [ ] **Step 8: Run everything**

```bash
./gradlew :connector:extensions:benefits-privacy:test
npm run build -w @benefits/consent-core && npm run build -w @benefits/consent-odrl
(cd packages/consent-odrl && npx vitest run)
```

Expected: Java 10 new tests pass; consent-odrl passes with the updated expectations.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat: kAnonymity and epsilonBudget constraint functions with emission"
```

---

### Task 9: Serve stored artifacts through the data plane

**Files:**
- Create: `.../BenefitsAggregateDataSourceFactory.java`
- Create: `.../src/test/java/org/benefits/policy/BenefitsAggregateDataSourceFactoryTest.java`
- Modify: `.../BenefitsPrivacyExtension.java`

**Interfaces:**
- Consumes: `ReleaseArtifactStore` from Task 7.
- Produces: a `DataSourceFactory` for type `BenefitsAggregate` that serves stored bytes and never computes.

- [ ] **Step 1: Write the failing test**

```java
package org.benefits.policy;

import org.eclipse.edc.spi.types.domain.DataAddress;
import org.eclipse.edc.spi.types.domain.transfer.DataFlowStartMessage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class BenefitsAggregateDataSourceFactoryTest {

    private final ReleaseArtifactStore store = new InMemoryReleaseArtifactStore();
    private final BenefitsAggregateDataSourceFactory factory = new BenefitsAggregateDataSourceFactory(store);

    private DataFlowStartMessage messageFor(String releaseRequestId) {
        var address = DataAddress.Builder.newInstance()
                .type(BenefitsAggregateDataSourceFactory.TYPE)
                .property("releaseRequestId", releaseRequestId)
                .build();
        return DataFlowStartMessage.Builder.newInstance()
                .processId("pid")
                .sourceDataAddress(address)
                .destinationDataAddress(DataAddress.Builder.newInstance().type("HttpData").build())
                .build();
    }

    @Test
    void supportsTheBenefitsAggregateType() {
        assertThat(factory.supportedType()).isEqualTo("BenefitsAggregate");
    }

    @Test
    void validationFailsWhenTheAddressCarriesNoReleaseRequestId() {
        var address = DataAddress.Builder.newInstance()
                .type(BenefitsAggregateDataSourceFactory.TYPE).build();
        var message = DataFlowStartMessage.Builder.newInstance()
                .processId("pid").sourceDataAddress(address)
                .destinationDataAddress(DataAddress.Builder.newInstance().type("HttpData").build())
                .build();
        assertThat(factory.validateRequest(message).succeeded()).isFalse();
    }

    @Test
    void validationFailsWhenNoArtifactIsStored() {
        assertThat(factory.validateRequest(messageFor("rr-absent")).succeeded()).isFalse();
    }

    @Test
    void validationSucceedsForAStoredArtifact() {
        store.save(new ReleaseArtifact("rr-1", "{}".getBytes(StandardCharsets.UTF_8), 6, 0.5));
        assertThat(factory.validateRequest(messageFor("rr-1")).succeeded()).isTrue();
    }

    @Test
    void servesTheStoredBytesUnchanged() throws Exception {
        var payload = "{\"groups\":[]}".getBytes(StandardCharsets.UTF_8);
        store.save(new ReleaseArtifact("rr-1", payload, 6, 0.5));
        var source = factory.createSource(messageFor("rr-1"));
        var part = source.openPartStream().getContent().findFirst().orElseThrow();
        assertThat(part.openStream().readAllBytes()).isEqualTo(payload);
    }

    @Test
    void servesIdenticalBytesOnRepeatedTransfers() throws Exception {
        store.save(new ReleaseArtifact("rr-1", "{\"groups\":[]}".getBytes(StandardCharsets.UTF_8), 6, 0.5));
        var first = factory.createSource(messageFor("rr-1")).openPartStream()
                .getContent().findFirst().orElseThrow().openStream().readAllBytes();
        var second = factory.createSource(messageFor("rr-1")).openPartStream()
                .getContent().findFirst().orElseThrow().openStream().readAllBytes();
        assertThat(first).isEqualTo(second);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :connector:extensions:benefits-privacy:test --tests '*BenefitsAggregateDataSourceFactoryTest*'`
Expected: compilation failure.

- [ ] **Step 3: Implement**

```java
package org.benefits.policy;

import org.eclipse.edc.connector.dataplane.spi.pipeline.DataSource;
import org.eclipse.edc.connector.dataplane.spi.pipeline.DataSourceFactory;
import org.eclipse.edc.connector.dataplane.spi.pipeline.InputStreamDataSource;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.types.domain.transfer.DataFlowStartMessage;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;

/**
 * Serves a previously computed anonymised artifact.
 * <p>
 * This factory NEVER computes. The artifact is produced once when the release request
 * is created and stored, so every transfer of the same release returns byte-identical
 * output. Computing here instead would let a requester re-run the transfer repeatedly
 * and average the results to recover the true value, while the privacy budget was
 * charged only once.
 * <p>
 * Because this is the only source registered for the BenefitsAggregate type, raw
 * contributions cannot leave through the release path.
 */
public class BenefitsAggregateDataSourceFactory implements DataSourceFactory {

    public static final String TYPE = "BenefitsAggregate";
    public static final String RELEASE_REQUEST_PROPERTY = "releaseRequestId";

    private final ReleaseArtifactStore store;

    public BenefitsAggregateDataSourceFactory(ReleaseArtifactStore store) {
        this.store = store;
    }

    @Override
    public String supportedType() {
        return TYPE;
    }

    @Override
    public DataSource createSource(DataFlowStartMessage request) {
        var artifact = artifactFor(request);
        return new InputStreamDataSource("benefits-aggregate",
                new ByteArrayInputStream(artifact.bytes()));
    }

    @Override
    public @NotNull Result<Void> validateRequest(DataFlowStartMessage request) {
        var releaseRequestId = releaseRequestId(request);
        if (releaseRequestId == null || releaseRequestId.isBlank()) {
            return Result.failure("source data address carries no " + RELEASE_REQUEST_PROPERTY);
        }
        if (store.findByReleaseRequestId(releaseRequestId) == null) {
            return Result.failure("no release artifact for release request " + releaseRequestId);
        }
        return Result.success();
    }

    private String releaseRequestId(DataFlowStartMessage request) {
        return request.getSourceDataAddress().getStringProperty(RELEASE_REQUEST_PROPERTY);
    }

    private ReleaseArtifact artifactFor(DataFlowStartMessage request) {
        var artifact = store.findByReleaseRequestId(releaseRequestId(request));
        if (artifact == null) {
            throw new IllegalStateException("no release artifact for " + releaseRequestId(request));
        }
        return artifact;
    }
}
```

- [ ] **Step 4: Register it alongside the probe**

In `BenefitsPrivacyExtension.initialize`, add after the probe registration:

```java
        pipelineService.registerFactory(new BenefitsAggregateDataSourceFactory(artifactStore));
```

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew :connector:extensions:benefits-privacy:test`
Expected: PASS, 6 new tests plus everything prior.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: serve stored anonymised artifacts through the data plane"
```

---

### Task 10: Read real contributions from CouchDB

**Files:**
- Create: `.../CouchDbContributionSource.java`
- Create: `.../src/test/java/org/benefits/policy/CouchDbContributionSourceIntegrationTest.java`
- Modify: `connector/extensions/benefits-privacy/build.gradle.kts` (Jackson)
- Modify: `connector/runtime/provider.properties`

**Interfaces:**
- Consumes: `ContributionSource`, `Contribution` from Task 2.
- Produces: a CouchDB-backed `ContributionSource`.

- [ ] **Step 1: Add Jackson and the config keys**

In `connector/extensions/benefits-privacy/build.gradle.kts` add:

```kotlin
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.0")
```

Append to `connector/runtime/provider.properties`:

```properties
benefits.couchdb.url=http://localhost:5984
benefits.couchdb.database=benefits
benefits.couchdb.user=admin
benefits.couchdb.password=benefits
```

- [ ] **Step 2: Write the failing integration test**

```java
package org.benefits.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class CouchDbContributionSourceIntegrationTest {

    private static final String BASE = "http://localhost:5984";
    private static final String DB = "benefits-privacy-it";
    private static final String AUTH = "Basic " + Base64.getEncoder()
            .encodeToString("admin:benefits".getBytes());
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static void send(String method, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(BASE + path))
                .header("authorization", AUTH).header("content-type", "application/json");
        var request = body == null
                ? builder.method(method, HttpRequest.BodyPublishers.noBody()).build()
                : builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build();
        CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @BeforeAll
    static void setUp() throws Exception {
        send("DELETE", "/" + DB, null);
        send("PUT", "/" + DB, null);
        send("POST", "/" + DB, """
                {"type":"contribution","participantRef":"p-1","siteId":"athens",
                 "channel":"app","fields":{"service.satisfaction":4}}""");
        send("POST", "/" + DB, """
                {"type":"contribution","participantRef":"p-2","siteId":"athens",
                 "channel":"sms","fields":{"service.satisfaction":5}}""");
        send("POST", "/" + DB, """
                {"type":"contribution","participantRef":"p-3","siteId":"mytilene",
                 "channel":"app","fields":{"service.satisfaction":3}}""");
    }

    @AfterAll
    static void tearDown() throws Exception {
        send("DELETE", "/" + DB, null);
    }

    @Test
    void readsOnlyContributionsForTheRequestedSite() {
        var source = new CouchDbContributionSource(BASE, DB, "admin", "benefits", new ObjectMapper());
        var athens = source.contributionsFor("athens");
        assertThat(athens).hasSize(2);
        assertThat(athens).extracting(Contribution::participantRef)
                .containsExactlyInAnyOrder("p-1", "p-2");
    }

    @Test
    void exposesQuasiIdentifiersAsAttributes() {
        var source = new CouchDbContributionSource(BASE, DB, "admin", "benefits", new ObjectMapper());
        var athens = source.contributionsFor("athens");
        assertThat(athens).extracting(c -> c.attribute("channel"))
                .containsExactlyInAnyOrder("app", "sms");
    }

    @Test
    void returnsEmptyForAnUnknownSite() {
        var source = new CouchDbContributionSource(BASE, DB, "admin", "benefits", new ObjectMapper());
        assertThat(source.contributionsFor("nowhere")).isEmpty();
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./gradlew :connector:extensions:benefits-privacy:test --tests '*CouchDb*'`
Expected: compilation failure. CouchDB must be running at localhost:5984; start it with `docker compose -f deploy/docker-compose.yml up -d` if it is not.

- [ ] **Step 4: Implement**

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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Reads contributions from CouchDB using a Mango query.
 * <p>
 * Only the site filter is pushed to the database. Grouping, suppression and noise all
 * happen in Java, so the privacy logic stays testable without a database.
 */
public class CouchDbContributionSource implements ContributionSource {

    private static final List<String> QUASI_IDENTIFIERS = List.of("channel", "service");

    private final String baseUrl;
    private final String database;
    private final String authorization;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();

    public CouchDbContributionSource(String baseUrl, String database, String user, String password, ObjectMapper mapper) {
        this.baseUrl = baseUrl;
        this.database = database;
        this.authorization = "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes());
        this.mapper = mapper;
    }

    @Override
    public List<Contribution> contributionsFor(String siteId) {
        var selector = """
                {"selector":{"type":"contribution","siteId":"%s"},"limit":100000}"""
                .formatted(siteId);

        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + database + "/_find"))
                .header("authorization", authorization)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(selector))
                .build();

        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new EdcException("CouchDB query failed with status " + response.statusCode());
            }
            return parse(mapper.readTree(response.body()));
        } catch (IOException e) {
            throw new EdcException("CouchDB query failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EdcException("CouchDB query interrupted", e);
        }
    }

    private List<Contribution> parse(JsonNode root) {
        List<Contribution> contributions = new ArrayList<>();
        for (JsonNode doc : root.path("docs")) {
            Map<String, String> attributes = new HashMap<>();
            for (var name : QUASI_IDENTIFIERS) {
                if (doc.hasNonNull(name)) {
                    attributes.put(name, doc.get(name).asText());
                }
            }
            for (Iterator<String> it = doc.path("fields").fieldNames(); it.hasNext(); ) {
                var field = it.next();
                attributes.put("field." + field, doc.path("fields").path(field).asText());
            }
            contributions.add(new Contribution(
                    doc.path("participantRef").asText(),
                    doc.path("siteId").asText(),
                    attributes));
        }
        return contributions;
    }
}
```

- [ ] **Step 5: Run to verify it passes**

```bash
docker compose -f deploy/docker-compose.yml up -d
until curl -sf http://admin:benefits@localhost:5984/_up >/dev/null; do sleep 1; done
./gradlew :connector:extensions:benefits-privacy:test --tests '*CouchDb*'
```

Expected: PASS, 3 tests.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: read contributions from CouchDB"
```

---

### Task 11: The two C1 privacy cases

**Files:**
- Modify: `tools/compile-policy.mjs`, `tools/run-case.sh`, `tools/violations.sh`, `tools/verify-cause.py`
- Modify: `.../BenefitsPrivacyExtension.java` (seed artifacts from config, mirroring the steward seeder)

**Interfaces:**
- Consumes: everything prior.
- Produces: two new rows in `docs/results/c1-consent-cases.md`.

- [ ] **Step 1: Seed artifacts from config**

Add to `BenefitsPrivacyExtension`, called as the last line of `initialize`, mirroring the steward authorization seeder already in `BenefitsPolicyExtension`:

```java
    private void seed(ServiceExtensionContext context) {
        var path = context.getSetting("benefits.seed.artifacts", "");
        if (path.isBlank()) {
            return;
        }
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            var json = mapper.readTree(java.nio.file.Files.readString(java.nio.file.Path.of(path)));
            for (var node : json) {
                artifactStore.save(new ReleaseArtifact(
                        node.path("releaseRequestId").asText(),
                        node.path("payload").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        node.path("computedK").asInt(),
                        node.path("epsilonCharged").asDouble()));
            }
            context.getMonitor().info("seeded release artifacts from " + path);
        } catch (java.io.IOException e) {
            throw new org.eclipse.edc.spi.EdcException("failed to seed release artifacts from " + path, e);
        }
    }
```

- [ ] **Step 2: Add the two cases to the driver**

In `tools/run-case.sh`, add to the case statement:

```bash
  k-below-threshold)            SEED="s-1 s-2"; ARTIFACT_K=4;   ARTIFACT_EPS=0.5 ;;
  budget-exceeded)              SEED="s-1 s-2"; ARTIFACT_K=6;   ARTIFACT_EPS=0.9 ;;
```

and give every existing case a satisfying artifact by defaulting before the case statement:

```bash
ARTIFACT_K=6
ARTIFACT_EPS=0.5
```

After the seed file is written, also write an artifact seed file and pass it:

```bash
python3 - "$CASE" "$WORK/artifacts.json" "$ARTIFACT_K" "$ARTIFACT_EPS" <<'PY'
import json, sys
case, out, k, eps = sys.argv[1], sys.argv[2], int(sys.argv[3]), float(sys.argv[4])
json.dump([{ "releaseRequestId": "rr-" + case, "payload": "{\"groups\":[]}",
             "computedK": k, "epsilonCharged": eps }], open(out, "w"))
PY
SEED_ARG+=(-Dbenefits.seed.artifacts="$WORK/artifacts.json")
```

- [ ] **Step 3: Add the expectations**

In `tools/violations.sh`, add to the `CASES` array:

```bash
  "k-below-threshold:TERMINATED"
  "budget-exceeded:TERMINATED"
```

- [ ] **Step 4: Add cause verification**

First, `tools/run-case.sh` must keep the artifact seed per case so the verifier can read it. Alongside the line that copies the consumer properties, add:

```bash
cp "$WORK/artifacts.json" "/tmp/artifacts-$CASE.json"
```

Then in `tools/verify-cause.py`, load the artifact next to the other per-case inputs, immediately after the `policy` line:

```python
artifact = json.load(open('/tmp/artifacts-%s.json' % case))[0]
```

and add these two branches alongside the existing `elif case == ...` blocks. Each isolates a single variable, exactly as the existing cases do: every other constraint is shown to be satisfied, so the named one is the only possible cause.

```python
elif case == 'k-below-threshold':
    require(bool(quorum) and quorum.groups() == ('2', '2'), 'quorum satisfied, signed=2 required=2')
    require(properties['benefits.requester.purpose'] == constraints['benefits:purpose'],
            'purpose matches')
    require(properties['benefits.requester.recipientClass'] == constraints['benefits:recipientClass'],
            'recipient class matches')
    require(all(f in granted for f in requested), 'requested fields are granted')
    require(float(artifact['epsilonCharged']) <= float(constraints['benefits:epsilonBudget']),
            'epsilon charged %s within policy cap %s, so budget is not the cause' % (
                artifact['epsilonCharged'], constraints['benefits:epsilonBudget']))
    require(int(artifact['computedK']) < int(constraints['benefits:kAnonymity']),
            'artifact computed k %s below policy threshold %s, so k is the only failing constraint' % (
                artifact['computedK'], constraints['benefits:kAnonymity']))
elif case == 'budget-exceeded':
    require(bool(quorum) and quorum.groups() == ('2', '2'), 'quorum satisfied, signed=2 required=2')
    require(properties['benefits.requester.purpose'] == constraints['benefits:purpose'],
            'purpose matches')
    require(properties['benefits.requester.recipientClass'] == constraints['benefits:recipientClass'],
            'recipient class matches')
    require(all(f in granted for f in requested), 'requested fields are granted')
    require(int(artifact['computedK']) >= int(constraints['benefits:kAnonymity']),
            'artifact computed k %s meets policy threshold %s, so k is not the cause' % (
                artifact['computedK'], constraints['benefits:kAnonymity']))
    require(float(artifact['epsilonCharged']) > float(constraints['benefits:epsilonBudget']),
            'artifact charged epsilon %s above policy cap %s, so budget is the only failing constraint' % (
                artifact['epsilonCharged'], constraints['benefits:epsilonBudget']))
```

- [ ] **Step 5: Run the full suite. THIS IS THE PHASE GATE.**

```bash
npm run build -w @benefits/consent-core && npm run build -w @benefits/consent-odrl
./gradlew shadowJar
./tools/violations.sh
```

Expected: 9 of 9, with `quorum-met` FINALIZED and the eight refusals each attributed to the right cause.

If `quorum-met` now terminates when it previously finalized, the two new constraints broke policy creation or evaluation. Report exactly what changed. Do not remove the constraints to make the suite pass.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "test: C1 privacy cases refused during real contract negotiation"
```

---

### Task 12: End to end, and prove the noise was drawn once

**Files:**
- Create: `tools/probe-aggregate-transfer.sh`
- Modify: `docs/results/` (generated)

**Interfaces:**
- Consumes: everything prior.
- Produces: evidence that a real transfer of a stored artifact returns byte-identical output twice.

- [ ] **Step 1: Write the end-to-end script**

`tools/probe-aggregate-transfer.sh`:

```bash
#!/usr/bin/env bash
# Transfers a stored anonymised artifact twice and proves the bytes are identical.
# Different bytes would mean the DataSource computed rather than served, which is the
# averaging-attack vulnerability the design exists to prevent.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

WORK=/tmp/benefits-e2e
rm -rf "$WORK" && mkdir -p "$WORK/first" "$WORK/second"

cat > "$WORK/artifacts.json" <<'JSON'
[{ "releaseRequestId": "rr-e2e", "payload": "{\"groups\":[{\"group\":\"app\",\"count\":6.4821}]}",
   "computedK": 6, "epsilonCharged": 0.5 }]
JSON

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

run_transfer() {
  local dir=$1
  python3 -m http.server 4000 --directory "$dir" >/dev/null 2>&1 &
  echo $! > "$WORK/sink.pid"
  sleep 1

  local agreement tp tp_id tstate
  agreement=$(curl -sf -H "$KEY" "$CONSUMER_MGMT/v3/contractnegotiations/$(cat /tmp/negotiation-id.txt)" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["contractAgreementId"])')

  tp=$(curl -sf -H "$KEY" -H "$JSON" -X POST "$CONSUMER_MGMT/v3/transferprocesses" -d "{
    \"@context\": { \"@vocab\": \"https://w3id.org/edc/v0.0.1/ns/\" },
    \"@type\": \"TransferRequestDto\",
    \"connectorId\": \"provider\",
    \"counterPartyAddress\": \"http://localhost:19194/protocol/2025-1\",
    \"contractId\": \"$agreement\",
    \"protocol\": \"dataspace-protocol-http:2025-1\",
    \"transferType\": \"HttpData-PUSH\",
    \"dataDestination\": { \"type\": \"HttpData\", \"baseUrl\": \"http://localhost:4000/\" }
  }")
  tp_id=$(echo "$tp" | python3 -c 'import json,sys; print(json.load(sys.stdin)["@id"])')

  for _ in $(seq 1 60); do
    tstate=$(curl -sf -H "$KEY" "$CONSUMER_MGMT/v3/transferprocesses/$tp_id" \
      | python3 -c 'import json,sys; print(json.load(sys.stdin).get("state",""))')
    case "$tstate" in COMPLETED|STARTED|TERMINATED) break ;; esac
    sleep 1
  done
  echo "transfer into $dir: $tstate"
  kill "$(cat "$WORK/sink.pid")" 2>/dev/null || true
  sleep 1
}

trap 'kill "$(cat "$WORK/sink.pid")" 2>/dev/null || true; stop_all' EXIT

stop_all
start_connector provider "$ROOT/connector/runtime/provider.properties" 19193 \
  -Dbenefits.seed.artifacts="$WORK/artifacts.json"
start_connector consumer "$ROOT/connector/runtime/consumer.properties" 29193

curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/assets" -d '{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@id": "benefits-aggregate-e2e",
  "properties": { "name": "aggregate" },
  "dataAddress": { "type": "BenefitsAggregate", "releaseRequestId": "rr-e2e" }
}' >/dev/null
curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/policydefinitions" -d @"$WORK/policy.json" >/dev/null
curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/contractdefinitions" -d '{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@id": "benefits-e2e-definition",
  "accessPolicyId": "benefits-policy",
  "contractPolicyId": "benefits-policy",
  "assetsSelector": []
}' >/dev/null

STATE=$(negotiate)
echo "negotiation: $STATE"
[ "$STATE" = "FINALIZED" ] || { echo "negotiation did not finalize" >&2; exit 1; }

run_transfer "$WORK/first"
run_transfer "$WORK/second"

FIRST=$(find "$WORK/first" -type f ! -name '*.pid' | head -1)
SECOND=$(find "$WORK/second" -type f ! -name '*.pid' | head -1)
[ -n "$FIRST" ] && [ -n "$SECOND" ] || { echo "a transfer delivered nothing" >&2; exit 1; }

if cmp -s "$FIRST" "$SECOND"; then
  echo "BYTE-IDENTICAL ACROSS TRANSFERS"
else
  echo "DIFFERENT BYTES ACROSS TRANSFERS: noise was redrawn, which is a defect" >&2
  exit 1
fi
```

```bash
chmod +x tools/probe-aggregate-transfer.sh
```

- [ ] **Step 2: Run it. THIS IS THE FINAL GATE.**

Run: `./gradlew shadowJar && ./tools/probe-aggregate-transfer.sh`
Expected: both transfers complete, and `BYTE-IDENTICAL ACROSS TRANSFERS`.

Different bytes would mean the DataSource computed rather than served, which is the averaging-attack vulnerability the design exists to prevent. That is a defect, not a flake; report it rather than re-running.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "test: prove a released artifact is byte-identical across transfers"
```

---

## Phase Gates

| Gate | What it proves | Task |
|---|---|---|
| 0 | A data plane exists and bytes actually move | 1 |
| 1 | k-anonymity and Laplace noise are correct and reproducible | 3, 4 |
| 2 | The budget accountant cannot overspend under concurrency | 5 |
| 3 | Both privacy constraints refuse at negotiation for the right reason | 11 |
| 4 | A released artifact is byte-identical across transfers | 12 |

If gate 0 fails and cannot be recovered within a day, stop and escalate. The spec records a pre-release service as the fallback, and choosing it is the controller's decision, not a workaround.

## What this plan does not cover

The audit chain (phase 4), the simulated device fleet (phase 5), the interfaces (phase 6), and the experiments (phase 7). Floating-point side channels in the Laplace mechanism, and mean or sum queries, are both out of scope per the spec.
