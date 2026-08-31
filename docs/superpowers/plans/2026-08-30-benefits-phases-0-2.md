# BENEFITS Phases 0 to 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the consent enforcement spine of the BENEFITS reference implementation: an edge filter that refuses unconsented writes inside CouchDB, and an Eclipse Dataspace Components connector that refuses contract negotiation without a community steward quorum.

**Architecture:** Consent is authored once as a per field rule set and compiled two ways. It compiles into a CouchDB `validate_doc_update` design document that blocks records leaving the participant device (gate S1), and into ODRL constraints that an EDC 0.18.0 policy extension evaluates at the release boundary (gate S2). Because the two enforcement points are written in different languages, a shared JSON conformance vector suite is the contract between them and both must decide every vector identically.

**Tech Stack:** TypeScript with npm workspaces and esbuild, Java 17 with Gradle and Eclipse Dataspace Components 0.18.0, CouchDB in Docker, Vitest for TypeScript tests, JUnit 5 for Java tests.

**Spec:** `docs/superpowers/specs/2026-08-30-benefits-reference-implementation-design.md`

## Global Constraints

- EDC is pinned to exactly **0.18.0**. Never float the version. The release changed authorization and signaling defaults and the next minor will change more.
- Gradle wrapper is **9.7.1**, vendored into the repo. Gradle is not installed on this machine and must not be installed.
- Java toolchain is **17**. Both 17 and 21 are present; 21 is the default, so the toolchain must be declared explicitly.
- The CouchDB image is pinned **by digest**, discovered in Task 3 and never floated.
- All timestamps everywhere are ISO 8601 UTC with millisecond precision and a trailing `Z`, for example `2026-08-30T12:00:00.000Z`. Consent expiry comparison relies on lexicographic string ordering, which is only correct for this exact format. Reject any other format at the boundary.
- Code that ends up inside `validate_doc_update` must be **ES5 syntax and ES5 runtime**. No `let`, `const`, arrow functions, template literals, `Map`, `Set`, `Object.assign`, `Array.prototype.includes`, or `JSON` beyond `parse`/`stringify`. Use `var`, plain objects, and indexed `for` loops. The CouchDB query server may be classic SpiderMonkey, which lowering cannot rescue at runtime.
- The BENEFITS JSON-LD namespace is `https://w3id.org/benefits/v1/ns/`. The EDC namespace is `https://w3id.org/edc/v0.0.1/ns/`.
- No emoji anywhere in code, log strings, comments, or commit messages.
- No AI or tooling attribution in commit messages, code comments, or documentation.
- No em-dashes in prose written into the repo. Use commas, colons, or periods.
- Every task ends with a commit. Commit messages use conventional prefixes (`feat:`, `test:`, `chore:`, `fix:`).

---

## File Structure

```
benefits/
  settings.gradle.kts                        Gradle project graph
  build.gradle.kts                           root Gradle config, Java toolchain
  gradle/libs.versions.toml                  pinned versions, single source of truth
  gradlew, gradlew.bat, gradle/wrapper/       vendored wrapper 9.7.1
  package.json                               npm workspaces root
  tsconfig.base.json                         shared TypeScript config

  conformance/vectors/*.json                 shared cross-language test vectors
  conformance/README.md                      vector format contract

  packages/consent-core/                     TypeScript consent semantics
    src/types.ts                             ConsentGrant, ContributionRecord, Decision
    src/evaluate.ts                          the S1 evaluator, ES5 safe
    src/meet.ts                              consent composition (meet-semilattice)
    src/index.ts                             public surface
    test/evaluate.test.ts, test/meet.test.ts, test/conformance.test.ts
  packages/consent-couchdb/                  bundles the evaluator into a design doc
    src/build-design-doc.ts
    test/design-doc.test.ts
    test/couchdb.integration.test.ts
  packages/consent-odrl/                     compiles a rule set into ODRL JSON-LD
    src/compile.ts
    test/compile.test.ts

  connector/extensions/benefits-policy/      the EDC extension
    build.gradle.kts
    src/main/java/org/benefits/policy/
      BenefitsNamespace.java                 IRI constants
      BenefitsPolicyExtension.java           registration and scope binding
      BenefitsIamExtension.java              mock IAM claims for purpose and recipient class
      StewardSignature.java                  record
      StewardAuthorization.java              record
      StewardAuthorizationStore.java         interface
      InMemoryStewardAuthorizationStore.java default implementation
      Constraints.java                       sibling constraint lookup helper
      StewardQuorumFunction.java             gate S2
      PurposeFunction.java                   consent derived constraint
      RecipientClassFunction.java            consent derived constraint
      FieldSetFunction.java                  consent derived constraint
    src/main/resources/META-INF/services/org.eclipse.edc.spi.system.ServiceExtension
    src/test/java/org/benefits/policy/       unit tests plus the Java conformance runner
  connector/runtime/                         assembled connector
    build.gradle.kts
    provider.properties, consumer.properties

  deploy/docker-compose.yml                  CouchDB, pinned by digest
  deploy/couchdb-engine-probe.md             recorded probe result

  tools/negotiate.sh                         Task 2 probe negotiation, standalone
  tools/lib.sh                               shared start, publish, negotiate helpers
  tools/run-case.sh                          drives one C1 case end to end
  tools/violations.sh                        runs every C1 case, writes the results table
  tools/compile-policy.mjs                   per case policy via the real consent-odrl compiler
  tools/contract-request.py                  builds a contract request from the live catalog
  tools/resources/*.json                     Task 2 Management API request bodies
  docs/results/c1-consent-cases.md           generated, goes into the manuscript
```

---

# Phase 0: Skeleton and de-risking

Phase 0 exists to convert the single biggest unknown in this project into a day one pass or fail. Approach C assumes EDC's extension points behave as the source suggests. Task 2 proves it with a real negotiation or reveals that it does not.

---

### Task 1: Repo skeleton and a connector runtime that boots

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`
- Create: `connector/runtime/build.gradle.kts`, `connector/runtime/provider.properties`
- Create: `gradlew`, `gradlew.bat`, `gradle/wrapper/` (copied, not generated)

**Interfaces:**
- Consumes: nothing.
- Produces: a shadow jar at `connector/runtime/build/libs/connector.jar` whose main class is `org.eclipse.edc.boot.system.runtime.BaseRuntime`, and a version catalog where all later tasks add dependencies.

- [ ] **Step 1: Vendor the Gradle wrapper**

Gradle is not installed and must not be installed. Copy the wrapper from the EDC Samples repo, which is already on 9.7.1.

```bash
cd /home/und3rd06012/benefits
git clone --depth 1 https://github.com/eclipse-edc/Samples.git /tmp/edc-samples
mkdir -p gradle
cp /tmp/edc-samples/gradlew /tmp/edc-samples/gradlew.bat .
cp -r /tmp/edc-samples/gradle/wrapper gradle/
chmod +x gradlew
grep distributionUrl gradle/wrapper/gradle-wrapper.properties
```

Expected: `distributionUrl` names `gradle-9.7.1-bin.zip`. If it names a different version, record the actual version in the commit message and continue; the wrapper version is not load bearing, only its presence is.

- [ ] **Step 2: Write the version catalog**

Create `gradle/libs.versions.toml`:

```toml
[versions]
edc = "0.18.0"
assertj = "3.26.3"
junit = "5.11.4"

[libraries]
edc-bom-controlplane-base = { module = "org.eclipse.edc:controlplane-base-bom", version.ref = "edc" }
edc-iam-mock = { module = "org.eclipse.edc:iam-mock", version.ref = "edc" }
edc-control-plane-spi = { module = "org.eclipse.edc:control-plane-spi", version.ref = "edc" }
edc-control-plane-core = { module = "org.eclipse.edc:control-plane-core", version.ref = "edc" }
edc-policy-engine-spi = { module = "org.eclipse.edc:policy-engine-spi", version.ref = "edc" }
edc-participant-context-config-spi = { module = "org.eclipse.edc:participant-context-config-spi", version.ref = "edc" }
edc-json-ld-spi = { module = "org.eclipse.edc:json-ld-spi", version.ref = "edc" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }
assertj-core = { module = "org.assertj:assertj-core", version.ref = "assertj" }

[plugins]
shadow = { id = "com.gradleup.shadow", version = "9.6.1" }
```

- [ ] **Step 3: Write the root build and settings**

Create `settings.gradle.kts`:

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
include(":connector:runtime")
```

Create `build.gradle.kts`:

```kotlin
plugins {
    `java-library`
}

subprojects {
    apply(plugin = "java-library")

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

    dependencies {
        add("testImplementation", rootProject.libs.junit.jupiter)
        add("testImplementation", rootProject.libs.assertj.core)
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}

val Project.libs: org.gradle.accessors.dm.LibrariesForLibs
    get() = extensions.getByType()
```

If the `libs` accessor inside `subprojects` fails to resolve, replace the `dependencies` block with explicit coordinates (`"org.junit.jupiter:junit-jupiter:5.11.4"` and `"org.assertj:assertj-core:3.26.3"`) and delete the accessor property. Do not spend more than one attempt on the accessor; it is convenience, not architecture.

- [ ] **Step 4: Create an empty extension module so the runtime has something to depend on**

Create `connector/extensions/benefits-policy/build.gradle.kts`:

```kotlin
plugins {
    `java-library`
}

dependencies {
    api(libs.edc.control.plane.spi)
    api(libs.edc.policy.engine.spi)
    api(libs.edc.participant.context.config.spi)
    api(libs.edc.json.ld.spi)
    implementation(libs.edc.control.plane.core)
}
```

Create the services file so the module is a valid extension module from the start:

```bash
mkdir -p connector/extensions/benefits-policy/src/main/resources/META-INF/services
touch connector/extensions/benefits-policy/src/main/resources/META-INF/services/org.eclipse.edc.spi.system.ServiceExtension
mkdir -p connector/extensions/benefits-policy/src/main/java/org/benefits/policy
```

- [ ] **Step 5: Create the runtime module**

Create `connector/runtime/build.gradle.kts`:

```kotlin
plugins {
    `java-library`
    id("application")
    alias(libs.plugins.shadow)
}

dependencies {
    runtimeOnly(libs.edc.bom.controlplane.base)
    runtimeOnly(libs.edc.iam.mock)
    implementation(project(":connector:extensions:benefits-policy"))
}

application {
    mainClass.set("org.eclipse.edc.boot.system.runtime.BaseRuntime")
}

tasks.shadowJar {
    mergeServiceFiles()
    archiveFileName.set("connector.jar")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}
```

Create `connector/runtime/provider.properties`:

```properties
web.http.port=19191
web.http.path=/api
web.http.management.port=19193
web.http.management.path=/management
web.http.protocol.port=19194
web.http.protocol.path=/protocol
web.http.control.port=19195
web.http.control.path=/control
web.http.signaling.port=19196
web.http.signaling.path=/signaling

web.http.management.auth.key=password
edc.dsp.callback.address=http://localhost:19194/protocol
edc.participant.id=provider
```

- [ ] **Step 6: Build and verify the jar exists**

Run: `./gradlew clean shadowJar`
Expected: BUILD SUCCESSFUL, and `connector/runtime/build/libs/connector.jar` exists.

```bash
./gradlew clean shadowJar && ls -la connector/runtime/build/libs/connector.jar
```

- [ ] **Step 7: Boot the runtime and verify the Management API answers**

```bash
java -Dedc.fs.config=connector/runtime/provider.properties \
     -jar connector/runtime/build/libs/connector.jar > /tmp/provider.log 2>&1 &
echo $! > /tmp/provider.pid
until grep -q "Runtime .* ready" /tmp/provider.log 2>/dev/null; do sleep 1; done
curl -s -H "x-api-key: password" http://localhost:19193/management/v3/assets/request -X POST \
     -H "content-type: application/json" -d '{}' | head -c 200
kill "$(cat /tmp/provider.pid)"
```

Expected: the log contains a readiness line, and the assets request returns `[]` or a JSON array rather than a connection error. If the readiness string differs, adjust the `until` grep to whatever the log actually prints and record the correct string in the commit message.

- [ ] **Step 8: Commit**

```bash
cd /home/und3rd06012/benefits
cat > .gitignore <<'EOF'
node_modules/
build/
.gradle/
dist/
experiments/results/*.csv
__pycache__/
.venv/
EOF
git add -A
git commit -m "chore: repo skeleton with EDC 0.18.0 runtime that boots"
```

---

### Task 2: Prove the EDC extension API with a real negotiation

This is the task that validates approach C. If a constraint function cannot be registered and observed evaluating during a real contract negotiation, the whole tier 3 design is wrong and we find out now rather than in Phase 2.

**Files:**
- Create: `connector/extensions/benefits-policy/src/main/java/org/benefits/policy/BenefitsNamespace.java`
- Create: `connector/extensions/benefits-policy/src/main/java/org/benefits/policy/BenefitsIamExtension.java`
- Create: `connector/extensions/benefits-policy/src/main/java/org/benefits/policy/ProbeConstraintFunction.java`
- Create: `connector/extensions/benefits-policy/src/main/java/org/benefits/policy/BenefitsPolicyExtension.java`
- Modify: `connector/extensions/benefits-policy/src/main/resources/META-INF/services/org.eclipse.edc.spi.system.ServiceExtension`
- Create: `connector/runtime/consumer.properties`
- Create: `tools/negotiate.sh`, `tools/resources/create-asset.json`, `tools/resources/create-policy.json`, `tools/resources/create-contract-definition.json`, `tools/resources/contract-request.json`

**Interfaces:**
- Consumes: the runtime and extension modules from Task 1.
- Produces: `BenefitsNamespace.BENEFITS_NAMESPACE` (String), a working `BenefitsPolicyExtension` that later tasks add registrations to, and `tools/negotiate.sh` which later tasks reuse.

- [ ] **Step 1: Write the namespace constants**

`BenefitsNamespace.java`:

```java
package org.benefits.policy;

public final class BenefitsNamespace {

    public static final String BENEFITS_NAMESPACE = "https://w3id.org/benefits/v1/ns/";

    public static final String PROBE_KEY = BENEFITS_NAMESPACE + "probe";

    private BenefitsNamespace() {
    }
}
```

- [ ] **Step 2: Write the probe constraint function**

`ProbeConstraintFunction.java`. It logs loudly on every evaluation so the negotiation test can assert that it ran, and it returns true so the negotiation succeeds.

```java
package org.benefits.policy;

import org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext;
import org.eclipse.edc.policy.engine.spi.AtomicConstraintRuleFunction;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.policy.model.Permission;
import org.eclipse.edc.spi.monitor.Monitor;

public class ProbeConstraintFunction implements AtomicConstraintRuleFunction<Permission, ContractNegotiationPolicyContext> {

    public static final String MARKER = "BENEFITS_PROBE_EVALUATED";

    private final Monitor monitor;

    public ProbeConstraintFunction(Monitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public boolean evaluate(Operator operator, Object rightValue, Permission rule, ContractNegotiationPolicyContext context) {
        monitor.info(MARKER + " operator=" + operator + " rightValue=" + rightValue + " constraints=" + rule.getConstraints().size());
        return true;
    }
}
```

- [ ] **Step 3: Write the mock IAM claims extension**

The connector needs a participant agent carrying claims, because Phase 2's purpose and recipient class functions read them. Establish the pattern now. `BenefitsIamExtension.java`:

```java
package org.benefits.policy;

import org.eclipse.edc.participantcontext.spi.config.ParticipantContextConfig;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Provider;
import org.eclipse.edc.spi.iam.AudienceResolver;
import org.eclipse.edc.spi.iam.ClaimToken;
import org.eclipse.edc.spi.iam.IdentityService;
import org.eclipse.edc.spi.iam.TokenParameters;
import org.eclipse.edc.spi.iam.TokenRepresentation;
import org.eclipse.edc.spi.iam.VerificationContext;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.types.TypeManager;

import java.util.Map;

public class BenefitsIamExtension implements ServiceExtension {

    @Inject
    private ParticipantContextConfig contextConfig;
    @Inject
    private TypeManager typeManager;
    @Inject
    private AudienceResolver audienceResolver;

    @Override
    public String name() {
        return "BENEFITS mock IAM claims";
    }

    @Provider
    public IdentityService identityService() {
        return new BenefitsClaimsIdentityService(contextConfig, typeManager);
    }

    private static class BenefitsClaimsIdentityService implements IdentityService {
        private final ParticipantContextConfig contextConfig;
        private final TypeManager typeManager;

        BenefitsClaimsIdentityService(ParticipantContextConfig contextConfig, TypeManager typeManager) {
            this.contextConfig = contextConfig;
            this.typeManager = typeManager;
        }

        @Override
        public Result<TokenRepresentation> obtainClientCredentials(String participantContextId, TokenParameters parameters) {
            var token = Map.of(
                    "client_id", contextConfig.getString(participantContextId, "edc.participant.id"),
                    "purpose", contextConfig.getString(participantContextId, "benefits.requester.purpose", ""),
                    "recipientClass", contextConfig.getString(participantContextId, "benefits.requester.recipientClass", "")
            );
            return Result.success(TokenRepresentation.Builder.newInstance()
                    .token(typeManager.writeValueAsString(token))
                    .build());
        }

        @Override
        @SuppressWarnings("unchecked")
        public Result<ClaimToken> verifyJwtToken(String participantContextId, TokenRepresentation tokenRepresentation, VerificationContext context) {
            Map<String, Object> token = typeManager.readValue(tokenRepresentation.getToken(), Map.class);
            return Result.success(ClaimToken.Builder.newInstance()
                    .claim("client_id", token.get("client_id"))
                    .claim("purpose", token.get("purpose"))
                    .claim("recipientClass", token.get("recipientClass"))
                    .build());
        }
    }
}
```

- [ ] **Step 4: Write the registration extension**

`BenefitsPolicyExtension.java`:

```java
package org.benefits.policy;

import org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext;
import org.eclipse.edc.policy.engine.spi.PolicyEngine;
import org.eclipse.edc.policy.engine.spi.RuleBindingRegistry;
import org.eclipse.edc.policy.model.Permission;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

import static org.benefits.policy.BenefitsNamespace.PROBE_KEY;
import static org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext.NEGOTIATION_SCOPE;
import static org.eclipse.edc.jsonld.spi.PropertyAndTypeNames.ODRL_USE_ACTION_ATTRIBUTE;
import static org.eclipse.edc.policy.engine.spi.PolicyEngine.ALL_SCOPES;

public class BenefitsPolicyExtension implements ServiceExtension {

    @Inject
    private RuleBindingRegistry ruleBindingRegistry;
    @Inject
    private PolicyEngine policyEngine;

    @Override
    public String name() {
        return "BENEFITS policy functions";
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        var monitor = context.getMonitor();

        ruleBindingRegistry.bind(ODRL_USE_ACTION_ATTRIBUTE, ALL_SCOPES);
        ruleBindingRegistry.bind(PROBE_KEY, NEGOTIATION_SCOPE);
        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class, PROBE_KEY,
                new ProbeConstraintFunction(monitor));

        monitor.info("BENEFITS policy functions registered");
    }
}
```

- [ ] **Step 5: Register both extensions**

Write `connector/extensions/benefits-policy/src/main/resources/META-INF/services/org.eclipse.edc.spi.system.ServiceExtension`:

```
org.benefits.policy.BenefitsIamExtension
org.benefits.policy.BenefitsPolicyExtension
```

- [ ] **Step 6: Write the consumer config**

Create `connector/runtime/consumer.properties`:

```properties
web.http.port=29191
web.http.path=/api
web.http.management.port=29193
web.http.management.path=/management
web.http.protocol.port=29194
web.http.protocol.path=/protocol
web.http.control.port=29195
web.http.control.path=/control
web.http.signaling.port=29196
web.http.signaling.path=/signaling

web.http.management.auth.key=password
edc.dsp.callback.address=http://localhost:29194/protocol
edc.participant.id=consumer
benefits.requester.purpose=service-improvement
benefits.requester.recipientClass=statistics
```

- [ ] **Step 7: Write the Management API request bodies**

`tools/resources/create-asset.json`:

```json
{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@id": "benefits-aggregate",
  "properties": { "name": "aggregate release", "contenttype": "application/json" },
  "dataAddress": { "type": "test" }
}
```

`tools/resources/create-policy.json`. Note the explicit `benefits` prefix in the context; without it the left operand expands into the EDC vocabulary and will not match the registered key.

```json
{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@id": "probe-policy",
  "policy": {
    "@context": [
      "http://www.w3.org/ns/odrl.jsonld",
      { "benefits": "https://w3id.org/benefits/v1/ns/" }
    ],
    "@type": "Set",
    "permission": [
      {
        "action": "use",
        "constraint": {
          "@type": "AtomicConstraint",
          "leftOperand": "benefits:probe",
          "operator": "eq",
          "rightOperand": "on"
        }
      }
    ],
    "prohibition": [],
    "obligation": []
  }
}
```

`tools/resources/create-contract-definition.json`:

```json
{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@id": "benefits-definition",
  "accessPolicyId": "probe-policy",
  "contractPolicyId": "probe-policy",
  "assetsSelector": [
    {
      "@type": "CriterionDto",
      "operandLeft": "https://w3id.org/edc/v0.0.1/ns/id",
      "operator": "=",
      "operandRight": "benefits-aggregate"
    }
  ]
}
```

`tools/resources/contract-request.json`. The `OFFER_ID` placeholder is substituted by the script from the catalog response; it is not a plan placeholder.

```json
{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "@type": "ContractRequest",
  "counterPartyId": "provider",
  "counterPartyAddress": "http://localhost:19194/protocol/2025-1",
  "protocol": "dataspace-protocol-http:2025-1",
  "policy": {
    "@context": [
      "http://www.w3.org/ns/odrl.jsonld",
      { "benefits": "https://w3id.org/benefits/v1/ns/" }
    ],
    "@id": "OFFER_ID",
    "@type": "Offer",
    "permission": [
      {
        "action": "use",
        "target": "benefits-aggregate",
        "constraint": {
          "@type": "AtomicConstraint",
          "leftOperand": "benefits:probe",
          "operator": "eq",
          "rightOperand": "on"
        }
      }
    ],
    "assigner": "provider",
    "target": "benefits-aggregate"
  }
}
```

- [ ] **Step 8: Write the negotiation driver**

`tools/negotiate.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/connector/runtime/build/libs/connector.jar"
RES="$ROOT/tools/resources"
PROVIDER_MGMT=http://localhost:19193/management
CONSUMER_MGMT=http://localhost:29193/management
KEY="x-api-key: password"
JSON="content-type: application/json"

cleanup() {
  [ -f /tmp/provider.pid ] && kill "$(cat /tmp/provider.pid)" 2>/dev/null || true
  [ -f /tmp/consumer.pid ] && kill "$(cat /tmp/consumer.pid)" 2>/dev/null || true
}
trap cleanup EXIT

start() {
  local name=$1 config=$2 port=$3
  java -Dedc.fs.config="$ROOT/connector/runtime/$config" -jar "$JAR" > "/tmp/$name.log" 2>&1 &
  echo $! > "/tmp/$name.pid"
  for _ in $(seq 1 60); do
    if curl -sf "http://localhost:$port/api/check/health" >/dev/null 2>&1 \
       || curl -sf -H "$KEY" -H "$JSON" -X POST "http://localhost:$((port + 2))/management/v3/assets/request" -d '{}' >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "$name did not become ready" >&2
  tail -40 "/tmp/$name.log" >&2
  return 1
}

start provider provider.properties 19191
start consumer consumer.properties 29191

curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/assets"              -d @"$RES/create-asset.json" >/dev/null
curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/policydefinitions"   -d @"$RES/create-policy.json" >/dev/null
curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/contractdefinitions" -d @"$RES/create-contract-definition.json" >/dev/null

CATALOG=$(curl -sf -H "$KEY" -H "$JSON" -X POST "$CONSUMER_MGMT/v3/catalogs/request" -d '{
  "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
  "counterPartyAddress": "http://localhost:19194/protocol/2025-1",
  "counterPartyId": "provider",
  "protocol": "dataspace-protocol-http:2025-1"
}')
echo "$CATALOG" > /tmp/catalog.json

OFFER_ID=$(python3 -c '
import json, sys
c = json.load(open("/tmp/catalog.json"))
def walk(n):
    if isinstance(n, dict):
        if n.get("@type") in ("odrl:Offer", "Offer") and "@id" in n:
            print(n["@id"]); sys.exit(0)
        for v in n.values(): walk(v)
    elif isinstance(n, list):
        for v in n: walk(v)
walk(c)
sys.exit(1)
')

sed "s|OFFER_ID|$OFFER_ID|" "$RES/contract-request.json" > /tmp/contract-request.json
NEG=$(curl -sf -H "$KEY" -H "$JSON" -X POST "$CONSUMER_MGMT/v3/contractnegotiations" -d @/tmp/contract-request.json)
NEG_ID=$(echo "$NEG" | python3 -c 'import json,sys; print(json.load(sys.stdin)["@id"])')

STATE=""
for _ in $(seq 1 60); do
  STATE=$(curl -sf -H "$KEY" "$CONSUMER_MGMT/v3/contractnegotiations/$NEG_ID" \
          | python3 -c 'import json,sys; print(json.load(sys.stdin).get("state",""))')
  case "$STATE" in FINALIZED|TERMINATED) break ;; esac
  sleep 1
done

echo "negotiation state: $STATE"
echo "$STATE" > /tmp/negotiation-state.txt
```

```bash
chmod +x tools/negotiate.sh
```

- [ ] **Step 9: Run it and verify the probe function actually evaluated**

Run: `./gradlew shadowJar && ./tools/negotiate.sh && grep -c BENEFITS_PROBE_EVALUATED /tmp/provider.log`

Expected: the script prints `negotiation state: FINALIZED`, and the grep count is 1 or more.

This is the gate for approach C. If the count is zero while the negotiation finalized, the constraint was silently not bound, which almost always means the left operand did not expand to `https://w3id.org/benefits/v1/ns/probe`. Debug by adding `monitor.info` of the policy in `BenefitsPolicyExtension`, or temporarily register the key as `EDC_NAMESPACE + "probe"` and use a bare `"leftOperand": "probe"` in both JSON files to confirm the mechanism works before fixing the namespace. Record whichever form works in `docs/superpowers/notes/edc-leftoperand-expansion.md`.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "feat: register a probe constraint function and prove it evaluates during negotiation"
```

---

### Task 3: CouchDB pinned by digest with a recorded query server engine

**Files:**
- Create: `deploy/docker-compose.yml`
- Create: `deploy/couchdb-engine-probe.md`

**Interfaces:**
- Consumes: nothing.
- Produces: a CouchDB reachable at `http://localhost:5984` with admin `admin:benefits`, and a recorded statement of whether the query server supports typed arrays, which decides whether E1's signature verifying arm is possible at all.

- [ ] **Step 1: Discover and pin the image digest**

```bash
cd /home/und3rd06012/benefits
docker pull couchdb:3
docker inspect --format '{{index .RepoDigests 0}}' couchdb:3
```

Record the printed digest. It looks like `couchdb@sha256:<hex>`. Use it verbatim in the next step.

- [ ] **Step 2: Write the compose file using the digest**

Create `deploy/docker-compose.yml`, substituting the digest discovered above for `couchdb@sha256:REPLACE_WITH_DISCOVERED_DIGEST`:

```yaml
services:
  couchdb:
    image: couchdb@sha256:REPLACE_WITH_DISCOVERED_DIGEST
    container_name: benefits-couchdb
    environment:
      COUCHDB_USER: admin
      COUCHDB_PASSWORD: benefits
    ports:
      - "5984:5984"
    volumes:
      - couchdb-data:/opt/couchdb/data

volumes:
  couchdb-data:
```

- [ ] **Step 3: Start it and confirm reachability**

```bash
docker compose -f deploy/docker-compose.yml up -d
until curl -sf http://admin:benefits@localhost:5984/_up >/dev/null; do sleep 1; done
curl -s http://admin:benefits@localhost:5984/ | python3 -m json.tool
```

Expected: JSON reporting `couchdb: Welcome` with a `version` field. Record the version.

- [ ] **Step 4: Probe the query server engine**

Write a design document whose validation function reports what the engine supports, then trigger it with a write.

```bash
curl -sf -X PUT http://admin:benefits@localhost:5984/probe

curl -sf -X PUT http://admin:benefits@localhost:5984/probe/_design/probe \
  -H 'content-type: application/json' -d '{
    "validate_doc_update": "function(newDoc, oldDoc, userCtx, secObj) { var caps = []; try { if (typeof Uint8Array !== \"undefined\") { caps.push(\"typedarray\"); } } catch (e) {} try { if (typeof Map !== \"undefined\") { caps.push(\"map\"); } } catch (e) {} try { if (typeof BigInt !== \"undefined\") { caps.push(\"bigint\"); } } catch (e) {} try { eval(\"var f = () => 1;\"); caps.push(\"arrow\"); } catch (e) {} throw({forbidden: \"CAPS:\" + caps.join(\",\")}); }"
  }'

curl -s -X POST http://admin:benefits@localhost:5984/probe \
  -H 'content-type: application/json' -d '{"x":1}'
```

Expected: a 403 response whose `reason` begins `CAPS:` followed by the supported capability list.

- [ ] **Step 5: Record the result**

Create `deploy/couchdb-engine-probe.md` and fill in the three values you actually observed:

```markdown
# CouchDB query server capability probe

Recorded on the date this task ran, against the digest pinned in
`deploy/docker-compose.yml`.

- CouchDB version reported by `GET /`: <paste the version string>
- Image digest: <paste the digest>
- Capabilities reported by the probe: <paste the CAPS list verbatim>

## What this decides

If `typedarray` is absent, the signature verifying arm of experiment E1
cannot run inside `validate_doc_update` at all, and E1 reports that as its
result rather than as a failure. If `typedarray` is present, E1 measures
the cost of pure JavaScript Ed25519 verification per document.

If `arrow` and `map` are absent, the query server is classic SpiderMonkey
and the ES5 constraint in the plan's global constraints is load bearing
rather than precautionary.
```

- [ ] **Step 6: Clean up the probe database and commit**

```bash
curl -sf -X DELETE http://admin:benefits@localhost:5984/probe
git add -A
git commit -m "feat: pin CouchDB by digest and record query server capabilities"
```

---

# Phase 1: Consent semantics and the S1 gate

---

### Task 4: TypeScript workspace, consent types, and the conformance vector contract

**Files:**
- Create: `package.json`, `tsconfig.base.json`
- Create: `packages/consent-core/package.json`, `packages/consent-core/tsconfig.json`
- Create: `packages/consent-core/src/types.ts`, `packages/consent-core/src/index.ts`
- Create: `conformance/README.md`, `conformance/vectors/v001-simple-allow.json`, `conformance/vectors/v002-missing-grant.json`

**Interfaces:**
- Consumes: nothing.
- Produces: the exported types `ConsentGrant`, `ConsentAssertion`, `ContributionRecord`, `Decision`, `RecipientClass`, and the on-disk vector format that both the TypeScript and Java conformance runners read.

- [ ] **Step 1: Create the workspace root**

`package.json`:

```json
{
  "name": "benefits",
  "private": true,
  "workspaces": ["packages/*"],
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

`tsconfig.base.json`:

```json
{
  "compilerOptions": {
    "target": "ES5",
    "lib": ["ES5"],
    "module": "ES2020",
    "moduleResolution": "bundler",
    "strict": true,
    "declaration": true,
    "esModuleInterop": true,
    "skipLibCheck": true
  }
}
```

The `ES5` target and `ES5` lib are deliberate. They make the compiler reject `Map`, `Set`, and `Array.prototype.includes` in `consent-core`, which is exactly the discipline the CouchDB validator needs.

- [ ] **Step 2: Create the consent-core package**

`packages/consent-core/package.json`:

```json
{
  "name": "@benefits/consent-core",
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

`packages/consent-core/tsconfig.json`:

```json
{
  "extends": "../../tsconfig.base.json",
  "compilerOptions": { "outDir": "dist", "rootDir": "src" },
  "include": ["src"]
}
```

- [ ] **Step 3: Write the types**

`packages/consent-core/src/types.ts`:

```typescript
export type FieldId = string;
export type PurposeId = string;
export type RecipientClass = 'community' | 'provider' | 'statistics';

export interface ConsentGrant {
  field: FieldId;
  purpose: PurposeId;
  recipientClass: RecipientClass;
  grantedAt: string;
  expiresAt: string | null;
}

export interface ConsentAssertion {
  participantRef: string;
  grants: ConsentGrant[];
  assertedAt: string;
}

export interface ContributionRecord {
  _id?: string;
  _rev?: string;
  _deleted?: boolean;
  type: string;
  participantRef: string;
  siteId: string;
  collectedAt: string;
  channel: 'app' | 'sms' | 'paper' | 'voice';
  declaredPurpose: PurposeId;
  declaredRecipientClass: RecipientClass;
  fields: { [field: string]: unknown };
  consent: ConsentAssertion;
}

export interface Decision {
  allowed: boolean;
  reason: string;
}
```

`Decision.reason` is required rather than optional so that both languages always produce a comparable value. Allowed decisions use the reason `ok`.

`packages/consent-core/src/index.ts`:

```typescript
export * from './types';
export { evaluateRecord } from './evaluate';
export { meet, meetAll } from './meet';
```

- [ ] **Step 4: Write the vector format contract**

`conformance/README.md`:

```markdown
# Conformance vectors

These vectors are the contract between the two consent enforcement points.
The TypeScript evaluator in `packages/consent-core` and the Java constraint
functions in `connector/extensions/benefits-policy` must decide every vector
identically. Neither implementation is authoritative; disagreement is a
defect in whichever one departs from this directory.

Each file is one vector:

    {
      "id": "v001-simple-allow",
      "description": "one field with an exactly matching unexpired grant",
      "now": "2026-08-30T12:00:00.000Z",
      "record": { ...a ContributionRecord... },
      "expected": { "allowed": true, "reason": "ok" }
    }

Reason codes are part of the contract, not diagnostics. The closed set is:

    ok                      the record is fully consented
    not-a-contribution      type is not "contribution", so S1 does not apply
    missing-consent-assertion
    participant-mismatch    consent.participantRef differs from record.participantRef
    no-grant:<fieldId>      no matching unexpired grant for that field

All timestamps are ISO 8601 UTC with millisecond precision and a trailing Z.
Expiry comparison is lexicographic string comparison, which is only correct
for that exact format.
```

- [ ] **Step 5: Write the first two vectors**

`conformance/vectors/v001-simple-allow.json`:

```json
{
  "id": "v001-simple-allow",
  "description": "one field with an exactly matching unexpired grant",
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
          "expiresAt": null
        }
      ]
    }
  },
  "expected": { "allowed": true, "reason": "ok" }
}
```

`conformance/vectors/v002-missing-grant.json`:

```json
{
  "id": "v002-missing-grant",
  "description": "a second field is present with no grant covering it",
  "now": "2026-08-30T12:00:00.000Z",
  "record": {
    "type": "contribution",
    "participantRef": "p-001",
    "siteId": "athens",
    "collectedAt": "2026-08-30T11:00:00.000Z",
    "channel": "app",
    "declaredPurpose": "service-improvement",
    "declaredRecipientClass": "statistics",
    "fields": { "service.satisfaction": 4, "health.condition": "asthma" },
    "consent": {
      "participantRef": "p-001",
      "assertedAt": "2026-08-01T00:00:00.000Z",
      "grants": [
        {
          "field": "service.satisfaction",
          "purpose": "service-improvement",
          "recipientClass": "statistics",
          "grantedAt": "2026-08-01T00:00:00.000Z",
          "expiresAt": null
        }
      ]
    }
  },
  "expected": { "allowed": false, "reason": "no-grant:health.condition" }
}
```

- [ ] **Step 6: Install and verify the workspace resolves**

Run: `npm install && npx tsc -p packages/consent-core/tsconfig.json --noEmit`
Expected: install succeeds; the compile fails with "Cannot find module './evaluate'" because Task 5 has not written it yet. That failure is correct at this point.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: consent types and the conformance vector contract"
```

---

### Task 5: The S1 evaluator

**Files:**
- Create: `packages/consent-core/src/evaluate.ts`
- Create: `packages/consent-core/test/evaluate.test.ts`
- Create: `packages/consent-core/test/conformance.test.ts`
- Create: `conformance/vectors/v003-expired-grant.json`, `v004-participant-mismatch.json`, `v005-purpose-mismatch.json`, `v006-recipient-class-mismatch.json`, `v007-not-a-contribution.json`, `v008-missing-consent.json`

**Interfaces:**
- Consumes: `types.ts` from Task 4.
- Produces: `evaluateRecord(record: ContributionRecord, nowIso: string): Decision`, imported by `consent-couchdb` in Task 7 and mirrored in Java in Task 13.

- [ ] **Step 1: Write the failing conformance test**

`packages/consent-core/test/conformance.test.ts`:

```typescript
import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { evaluateRecord } from '../src/evaluate';

const dir = join(__dirname, '../../../conformance/vectors');

describe('conformance vectors', () => {
  const files = readdirSync(dir).filter((f) => f.endsWith('.json')).sort();

  it('has vectors to run', () => {
    expect(files.length).toBeGreaterThan(0);
  });

  files.forEach((file) => {
    const vector = JSON.parse(readFileSync(join(dir, file), 'utf8'));
    it(`${vector.id}: ${vector.description}`, () => {
      const decision = evaluateRecord(vector.record, vector.now);
      expect(decision).toEqual(vector.expected);
    });
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd packages/consent-core && npx vitest run test/conformance.test.ts`
Expected: FAIL, because `../src/evaluate` does not exist.

- [ ] **Step 3: Write the evaluator**

`packages/consent-core/src/evaluate.ts`. Written in ES5 style deliberately, because this exact source is bundled into the CouchDB validation function in Task 7.

```typescript
import { ConsentGrant, ContributionRecord, Decision } from './types';

export function evaluateRecord(record: ContributionRecord, nowIso: string): Decision {
  if (!record || record.type !== 'contribution') {
    return { allowed: true, reason: 'not-a-contribution' };
  }

  var consent = record.consent;
  if (!consent || !consent.grants) {
    return { allowed: false, reason: 'missing-consent-assertion' };
  }

  if (consent.participantRef !== record.participantRef) {
    return { allowed: false, reason: 'participant-mismatch' };
  }

  var fields = record.fields || {};
  var names: string[] = [];
  for (var key in fields) {
    if (Object.prototype.hasOwnProperty.call(fields, key)) {
      names.push(key);
    }
  }
  names.sort();

  for (var i = 0; i < names.length; i++) {
    if (!hasGrant(consent.grants, names[i], record.declaredPurpose, record.declaredRecipientClass, nowIso)) {
      return { allowed: false, reason: 'no-grant:' + names[i] };
    }
  }

  return { allowed: true, reason: 'ok' };
}

function hasGrant(
  grants: ConsentGrant[],
  field: string,
  purpose: string,
  recipientClass: string,
  nowIso: string
): boolean {
  for (var i = 0; i < grants.length; i++) {
    var g = grants[i];
    if (g.field !== field) { continue; }
    if (g.purpose !== purpose) { continue; }
    if (g.recipientClass !== recipientClass) { continue; }
    if (g.grantedAt > nowIso) { continue; }
    if (g.expiresAt !== null && g.expiresAt <= nowIso) { continue; }
    return true;
  }
  return false;
}
```

`names.sort()` matters: without it, the reason code for a record missing several grants would depend on object key order and the two implementations could disagree. Sorting makes the first failing field deterministic.

- [ ] **Step 4: Run the conformance test to verify the first two vectors pass**

Run: `cd packages/consent-core && npx vitest run test/conformance.test.ts`
Expected: PASS for v001 and v002.

- [ ] **Step 5: Add the remaining six vectors**

Create each file in `conformance/vectors/`. Each reuses the v001 record shape with one field changed.

`v003-expired-grant.json`: same as v001, but the grant has `"expiresAt": "2026-08-15T00:00:00.000Z"`. Expected `{ "allowed": false, "reason": "no-grant:service.satisfaction" }`.

`v004-participant-mismatch.json`: same as v001, but `consent.participantRef` is `"p-002"`. Expected `{ "allowed": false, "reason": "participant-mismatch" }`.

`v005-purpose-mismatch.json`: same as v001, but `declaredPurpose` is `"advocacy"` while the grant still says `"service-improvement"`. Expected `{ "allowed": false, "reason": "no-grant:service.satisfaction" }`.

`v006-recipient-class-mismatch.json`: same as v001, but `declaredRecipientClass` is `"provider"` while the grant still says `"statistics"`. Expected `{ "allowed": false, "reason": "no-grant:service.satisfaction" }`.

`v007-not-a-contribution.json`: same as v001, but `"type": "audit-pointer"`. Expected `{ "allowed": true, "reason": "not-a-contribution" }`.

`v008-missing-consent.json`: same as v001, but the `consent` property is removed entirely. Expected `{ "allowed": false, "reason": "missing-consent-assertion" }`.

- [ ] **Step 6: Write the boundary unit tests**

`packages/consent-core/test/evaluate.test.ts`:

```typescript
import { describe, it, expect } from 'vitest';
import { evaluateRecord } from '../src/evaluate';
import { ContributionRecord } from '../src/types';

function record(overrides: Partial<ContributionRecord> = {}): ContributionRecord {
  return {
    type: 'contribution',
    participantRef: 'p-001',
    siteId: 'athens',
    collectedAt: '2026-08-30T11:00:00.000Z',
    channel: 'app',
    declaredPurpose: 'service-improvement',
    declaredRecipientClass: 'statistics',
    fields: { 'service.satisfaction': 4 },
    consent: {
      participantRef: 'p-001',
      assertedAt: '2026-08-01T00:00:00.000Z',
      grants: [
        {
          field: 'service.satisfaction',
          purpose: 'service-improvement',
          recipientClass: 'statistics',
          grantedAt: '2026-08-01T00:00:00.000Z',
          expiresAt: null
        }
      ]
    },
    ...overrides
  };
}

describe('evaluateRecord boundaries', () => {
  it('treats expiry as exclusive at the exact instant', () => {
    const r = record();
    r.consent.grants[0].expiresAt = '2026-08-30T12:00:00.000Z';
    expect(evaluateRecord(r, '2026-08-30T12:00:00.000Z').allowed).toBe(false);
  });

  it('allows one millisecond before expiry', () => {
    const r = record();
    r.consent.grants[0].expiresAt = '2026-08-30T12:00:00.001Z';
    expect(evaluateRecord(r, '2026-08-30T12:00:00.000Z').allowed).toBe(true);
  });

  it('rejects a grant that is not yet active', () => {
    const r = record();
    r.consent.grants[0].grantedAt = '2026-09-01T00:00:00.000Z';
    expect(evaluateRecord(r, '2026-08-30T12:00:00.000Z').allowed).toBe(false);
  });

  it('allows a record with no fields at all', () => {
    const r = record({ fields: {} });
    expect(evaluateRecord(r, '2026-08-30T12:00:00.000Z')).toEqual({ allowed: true, reason: 'ok' });
  });

  it('reports the alphabetically first ungranted field', () => {
    const r = record({ fields: { 'z.field': 1, 'a.field': 2 } });
    expect(evaluateRecord(r, '2026-08-30T12:00:00.000Z').reason).toBe('no-grant:a.field');
  });
});
```

- [ ] **Step 7: Run all tests**

Run: `cd packages/consent-core && npx vitest run`
Expected: PASS, 8 conformance vectors plus 5 boundary tests.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: S1 consent evaluator passing eight conformance vectors"
```

---

### Task 6: The meet operation

**Files:**
- Create: `packages/consent-core/src/meet.ts`
- Create: `packages/consent-core/test/meet.test.ts`

**Interfaces:**
- Consumes: `ConsentGrant` from `types.ts`.
- Produces: `meet(a: ConsentGrant[], b: ConsentGrant[]): ConsentGrant[]` and `meetAll(sets: ConsentGrant[][]): ConsentGrant[]`, used by `consent-odrl` in Task 9 to derive an aggregate's policy.

- [ ] **Step 1: Write the failing test**

`packages/consent-core/test/meet.test.ts`:

```typescript
import { describe, it, expect } from 'vitest';
import { meet, meetAll } from '../src/meet';
import { ConsentGrant } from '../src/types';

function grant(field: string, expiresAt: string | null = null, grantedAt = '2026-08-01T00:00:00.000Z'): ConsentGrant {
  return { field, purpose: 'service-improvement', recipientClass: 'statistics', grantedAt, expiresAt };
}

describe('meet', () => {
  it('keeps only triples present in both sets', () => {
    const result = meet([grant('a'), grant('b')], [grant('b'), grant('c')]);
    expect(result.map((g) => g.field)).toEqual(['b']);
  });

  it('takes the earliest expiry', () => {
    const result = meet([grant('a', '2026-12-01T00:00:00.000Z')], [grant('a', '2026-10-01T00:00:00.000Z')]);
    expect(result[0].expiresAt).toBe('2026-10-01T00:00:00.000Z');
  });

  it('treats null expiry as unbounded', () => {
    const result = meet([grant('a', null)], [grant('a', '2026-10-01T00:00:00.000Z')]);
    expect(result[0].expiresAt).toBe('2026-10-01T00:00:00.000Z');
  });

  it('takes the latest grantedAt', () => {
    const result = meet(
      [grant('a', null, '2026-08-01T00:00:00.000Z')],
      [grant('a', null, '2026-09-01T00:00:00.000Z')]
    );
    expect(result[0].grantedAt).toBe('2026-09-01T00:00:00.000Z');
  });

  it('does not match across differing purpose', () => {
    const other = { ...grant('a'), purpose: 'advocacy' };
    expect(meet([grant('a')], [other])).toEqual([]);
  });

  it('is idempotent', () => {
    const set = [grant('a'), grant('b')];
    expect(meet(set, set)).toEqual(set);
  });

  it('is commutative in field membership', () => {
    const left = meet([grant('a'), grant('b')], [grant('b')]);
    const right = meet([grant('b')], [grant('a'), grant('b')]);
    expect(left).toEqual(right);
  });

  it('meetAll of an empty list is empty', () => {
    expect(meetAll([])).toEqual([]);
  });

  it('meetAll folds across three contributors', () => {
    const result = meetAll([[grant('a'), grant('b')], [grant('b'), grant('c')], [grant('b')]]);
    expect(result.map((g) => g.field)).toEqual(['b']);
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd packages/consent-core && npx vitest run test/meet.test.ts`
Expected: FAIL, module not found.

- [ ] **Step 3: Write the implementation**

`packages/consent-core/src/meet.ts`:

```typescript
import { ConsentGrant } from './types';

export function meet(a: ConsentGrant[], b: ConsentGrant[]): ConsentGrant[] {
  var out: ConsentGrant[] = [];
  for (var i = 0; i < a.length; i++) {
    for (var j = 0; j < b.length; j++) {
      var x = a[i];
      var y = b[j];
      if (x.field === y.field && x.purpose === y.purpose && x.recipientClass === y.recipientClass) {
        out.push({
          field: x.field,
          purpose: x.purpose,
          recipientClass: x.recipientClass,
          grantedAt: x.grantedAt > y.grantedAt ? x.grantedAt : y.grantedAt,
          expiresAt: earliest(x.expiresAt, y.expiresAt)
        });
      }
    }
  }
  return out;
}

export function meetAll(sets: ConsentGrant[][]): ConsentGrant[] {
  if (sets.length === 0) {
    return [];
  }
  var acc = sets[0];
  for (var i = 1; i < sets.length; i++) {
    acc = meet(acc, sets[i]);
  }
  return acc;
}

function earliest(p: string | null, q: string | null): string | null {
  if (p === null) { return q; }
  if (q === null) { return p; }
  return p < q ? p : q;
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd packages/consent-core && npx vitest run`
Expected: PASS, all meet tests plus the earlier suites.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: consent meet operation for aggregate composition"
```

---

### Task 7: Bundle the evaluator into a CouchDB design document

**Files:**
- Create: `packages/consent-couchdb/package.json`, `packages/consent-couchdb/tsconfig.json`
- Create: `packages/consent-couchdb/src/build-design-doc.ts`
- Create: `packages/consent-couchdb/test/design-doc.test.ts`

**Interfaces:**
- Consumes: `evaluateRecord` from `@benefits/consent-core`.
- Produces: `buildDesignDoc(): { _id: string; validate_doc_update: string }`, consumed by Task 8's integration test.

- [ ] **Step 1: Write the failing test**

`packages/consent-couchdb/test/design-doc.test.ts`:

```typescript
import { describe, it, expect } from 'vitest';
import { buildDesignDoc } from '../src/build-design-doc';

describe('buildDesignDoc', () => {
  it('produces a design doc with the expected id', () => {
    expect(buildDesignDoc()._id).toBe('_design/consent');
  });

  it('contains no ES2015 syntax that classic SpiderMonkey would reject', () => {
    const fn = buildDesignDoc().validate_doc_update;
    expect(fn).not.toMatch(/=>/);
    expect(fn).not.toMatch(/\blet\b/);
    expect(fn).not.toMatch(/\bconst\b/);
    expect(fn).not.toMatch(/`/);
    expect(fn).not.toMatch(/\bnew Map\b/);
    expect(fn).not.toMatch(/\bnew Set\b/);
  });

  it('evaluates as a function that forbids an unconsented write', () => {
    const fn = buildDesignDoc().validate_doc_update;
    // eslint-disable-next-line no-new-func
    const validate = new Function('return ' + fn)();
    const doc = {
      type: 'contribution',
      participantRef: 'p-001',
      siteId: 'athens',
      collectedAt: '2026-08-30T11:00:00.000Z',
      channel: 'app',
      declaredPurpose: 'service-improvement',
      declaredRecipientClass: 'statistics',
      fields: { 'health.condition': 'asthma' },
      consent: { participantRef: 'p-001', assertedAt: '2026-08-01T00:00:00.000Z', grants: [] }
    };
    expect(() => validate(doc, null, { name: 'p-001', roles: [] }, {}))
      .toThrowError(/no-grant:health.condition/);
  });

  it('permits a fully consented write', () => {
    const fn = buildDesignDoc().validate_doc_update;
    // eslint-disable-next-line no-new-func
    const validate = new Function('return ' + fn)();
    const doc = {
      type: 'contribution',
      participantRef: 'p-001',
      siteId: 'athens',
      collectedAt: '2026-08-30T11:00:00.000Z',
      channel: 'app',
      declaredPurpose: 'service-improvement',
      declaredRecipientClass: 'statistics',
      fields: { 'service.satisfaction': 4 },
      consent: {
        participantRef: 'p-001',
        assertedAt: '2026-08-01T00:00:00.000Z',
        grants: [{
          field: 'service.satisfaction',
          purpose: 'service-improvement',
          recipientClass: 'statistics',
          grantedAt: '2026-08-01T00:00:00.000Z',
          expiresAt: null
        }]
      }
    };
    expect(() => validate(doc, null, { name: 'p-001', roles: [] }, {})).not.toThrow();
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd packages/consent-couchdb && npx vitest run`
Expected: FAIL, module not found.

- [ ] **Step 3: Create the package**

`packages/consent-couchdb/package.json`:

```json
{
  "name": "@benefits/consent-couchdb",
  "version": "0.1.0",
  "type": "module",
  "main": "dist/index.js",
  "scripts": {
    "build": "tsc -p tsconfig.json",
    "test": "vitest run"
  },
  "dependencies": {
    "@benefits/consent-core": "0.1.0"
  }
}
```

`packages/consent-couchdb/tsconfig.json`:

```json
{
  "extends": "../../tsconfig.base.json",
  "compilerOptions": {
    "outDir": "dist",
    "rootDir": "src",
    "lib": ["ES5", "ES2015.Promise", "DOM"]
  },
  "include": ["src"]
}
```

- [ ] **Step 4: Write the bundler**

`packages/consent-couchdb/src/build-design-doc.ts`:

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
    '  var ConsentCore;',
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

If esbuild reports that it cannot lower a construct to es5, that is a real signal: fix the offending line in `consent-core/src/evaluate.ts` rather than raising the target. The ES5 constraint is load bearing.

- [ ] **Step 5: Run to verify it passes**

Run: `cd packages/consent-couchdb && npx vitest run`
Expected: PASS, all four tests.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: bundle the consent evaluator into a CouchDB design document"
```

---

### Task 8: Real CouchDB refuses unconsented writes

This is the Phase 1 gate. The evaluator working in Node proves nothing about the sealed query server engine.

**Files:**
- Create: `packages/consent-couchdb/test/couchdb.integration.test.ts`
- Modify: `packages/consent-couchdb/package.json` (add the `test:integration` script)

**Interfaces:**
- Consumes: `buildDesignDoc` from Task 7, the CouchDB instance from Task 3.
- Produces: nothing new; this is a verification task.

- [ ] **Step 1: Write the integration test**

`packages/consent-couchdb/test/couchdb.integration.test.ts`:

```typescript
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { buildDesignDoc } from '../src/build-design-doc';

const BASE = 'http://admin:benefits@localhost:5984';
const DB = 'benefits-conformance';
const vectorDir = join(__dirname, '../../../conformance/vectors');

async function req(method: string, path: string, body?: unknown) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: { 'content-type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  return { status: res.status, body: await res.json().catch(() => ({})) };
}

describe('CouchDB enforces S1', () => {
  beforeAll(async () => {
    await req('DELETE', `/${DB}`);
    const created = await req('PUT', `/${DB}`);
    expect([201, 202]).toContain(created.status);
    const dd = buildDesignDoc();
    const put = await req('PUT', `/${DB}/${dd._id}`, dd);
    expect([201, 202]).toContain(put.status);
  }, 30000);

  afterAll(async () => {
    await req('DELETE', `/${DB}`);
  });

  const files = readdirSync(vectorDir).filter((f) => f.endsWith('.json')).sort();

  files.forEach((file) => {
    const vector = JSON.parse(readFileSync(join(vectorDir, file), 'utf8'));
    it(`${vector.id} decided identically by CouchDB`, async () => {
      const res = await req('POST', `/${DB}`, { ...vector.record, _id: vector.id });
      if (vector.expected.allowed || vector.expected.reason === 'not-a-contribution') {
        expect(res.status).toBe(201);
      } else {
        expect(res.status).toBe(403);
        expect((res.body as { reason?: string }).reason).toBe(vector.expected.reason);
      }
    });
  });

  it('refuses a write whose consent assertion was stripped in transit', async () => {
    const vector = JSON.parse(readFileSync(join(vectorDir, 'v001-simple-allow.json'), 'utf8'));
    const tampered = { ...vector.record, _id: 'tampered', consent: undefined };
    const res = await req('POST', `/${DB}`, tampered);
    expect(res.status).toBe(403);
    expect((res.body as { reason?: string }).reason).toBe('missing-consent-assertion');
  });
});
```

Note that the vectors use a fixed `now` while CouchDB uses wall clock time. The vectors are written so that every timestamp is either unbounded or far enough from the present that both agree. If v003 (expired grant) ever disagrees, the vector's expiry is the problem, not the validator.

- [ ] **Step 2: Add the script**

In `packages/consent-couchdb/package.json`, add to `scripts`:

```json
"test:integration": "vitest run test/couchdb.integration.test.ts"
```

- [ ] **Step 3: Run against real CouchDB**

```bash
cd /home/und3rd06012/benefits
docker compose -f deploy/docker-compose.yml up -d
until curl -sf http://admin:benefits@localhost:5984/_up >/dev/null; do sleep 1; done
cd packages/consent-couchdb && npx vitest run test/couchdb.integration.test.ts
```

Expected: PASS. Every vector decided by CouchDB matches the decision the TypeScript evaluator made in Task 5, and the stripped-consent write is refused.

If the design document upload succeeds but every write is allowed, the validation function threw during parsing and CouchDB silently ignored it. Check with:

```bash
curl -s http://admin:benefits@localhost:5984/benefits-conformance/_design/consent | python3 -m json.tool | head -20
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "test: CouchDB enforces S1 identically to the TypeScript evaluator"
```

---

# Phase 2: ODRL compilation and the S2 steward gate

---

### Task 9: Compile a consent rule set into an ODRL policy

**Files:**
- Create: `packages/consent-odrl/package.json`, `packages/consent-odrl/tsconfig.json`
- Create: `packages/consent-odrl/src/compile.ts`
- Create: `packages/consent-odrl/test/compile.test.ts`

**Interfaces:**
- Consumes: `ConsentGrant`, `meetAll` from `@benefits/consent-core`.
- Produces: `compilePolicy(grants: ConsentGrant[], options: CompileOptions): object`, whose output is posted verbatim as the `policy` member of a `/v3/policydefinitions` request in Task 14.

- [ ] **Step 1: Write the failing test**

`packages/consent-odrl/test/compile.test.ts`:

```typescript
import { describe, it, expect } from 'vitest';
import { compilePolicy } from '../src/compile';
import { ConsentGrant } from '@benefits/consent-core';

const AT = '2026-08-30T12:00:00.000Z';

const grants: ConsentGrant[] = [
  { field: 'service.satisfaction', purpose: 'service-improvement', recipientClass: 'statistics', grantedAt: '2026-08-01T00:00:00.000Z', expiresAt: null },
  { field: 'service.waitTime', purpose: 'service-improvement', recipientClass: 'statistics', grantedAt: '2026-08-01T00:00:00.000Z', expiresAt: null }
];

function constraintFor(policy: any, leftOperand: string) {
  return policy.permission[0].constraint.find((c: any) => c.leftOperand === leftOperand);
}

describe('compilePolicy', () => {
  const policy = compilePolicy(grants, { releaseRequestId: 'rr-42', quorum: 2, kAnonymity: 5, epsilon: 0.5, at: AT });

  it('declares the benefits prefix in the context', () => {
    expect(policy['@context']).toContainEqual({ benefits: 'https://w3id.org/benefits/v1/ns/' });
  });

  it('emits a single use permission', () => {
    expect(policy.permission).toHaveLength(1);
    expect(policy.permission[0].action).toBe('use');
  });

  it('carries the release request id as a sibling constraint', () => {
    expect(constraintFor(policy, 'benefits:releaseRequest')).toEqual({
      '@type': 'AtomicConstraint',
      leftOperand: 'benefits:releaseRequest',
      operator: 'eq',
      rightOperand: 'rr-42'
    });
  });

  it('emits the quorum constraint with gteq', () => {
    expect(constraintFor(policy, 'benefits:stewardQuorum')).toEqual({
      '@type': 'AtomicConstraint',
      leftOperand: 'benefits:stewardQuorum',
      operator: 'gteq',
      rightOperand: 2
    });
  });

  it('derives the purpose set from the grants', () => {
    expect(constraintFor(policy, 'benefits:purpose').rightOperand).toEqual(['service-improvement']);
  });

  it('derives the field set from the grants, sorted', () => {
    expect(constraintFor(policy, 'benefits:fieldSet').rightOperand)
      .toEqual(['service.satisfaction', 'service.waitTime']);
  });

  it('rejects an empty grant set rather than emitting an unconstrained policy', () => {
    expect(() => compilePolicy([], { releaseRequestId: 'rr-1', quorum: 2, kAnonymity: 5, epsilon: 0.5, at: AT }))
      .toThrowError(/empty consent/);
  });

  it('rejects grants spanning more than one recipient class', () => {
    const mixed = [...grants, { ...grants[0], recipientClass: 'provider' as const }];
    expect(() => compilePolicy(mixed, { releaseRequestId: 'rr-1', quorum: 2, kAnonymity: 5, epsilon: 0.5, at: AT }))
      .toThrowError(/single recipient class/);
  });

  it('excludes a grant that has already expired at the compile instant', () => {
    const withExpired = [...grants, {
      field: 'health.condition',
      purpose: 'service-improvement',
      recipientClass: 'statistics' as const,
      grantedAt: '2026-08-01T00:00:00.000Z',
      expiresAt: '2026-08-15T00:00:00.000Z'
    }];
    const compiled = compilePolicy(withExpired, { releaseRequestId: 'rr-9', quorum: 2, kAnonymity: 5, epsilon: 0.5, at: AT });
    expect(constraintFor(compiled, 'benefits:fieldSet').rightOperand).not.toContain('health.condition');
  });

  it('keeps a grant expiring one millisecond after the compile instant', () => {
    const withFuture = [{ ...grants[0], expiresAt: '2026-08-30T12:00:00.001Z' }];
    const compiled = compilePolicy(withFuture, { releaseRequestId: 'rr-10', quorum: 2, kAnonymity: 5, epsilon: 0.5, at: AT });
    expect(constraintFor(compiled, 'benefits:fieldSet').rightOperand).toEqual(['service.satisfaction']);
  });

  it('rejects a rule set whose every grant has expired', () => {
    const allExpired = grants.map((g) => ({ ...g, expiresAt: '2026-08-15T00:00:00.000Z' }));
    expect(() => compilePolicy(allExpired, { releaseRequestId: 'rr-11', quorum: 2, kAnonymity: 5, epsilon: 0.5, at: AT }))
      .toThrowError(/empty consent/);
  });
});
```

The last test encodes a real design rule: an aggregate release targets exactly one recipient class, because the meet has already narrowed it. A rule set spanning several classes means the caller skipped the meet.

- [ ] **Step 2: Run it to verify it fails**

Run: `cd packages/consent-odrl && npx vitest run`
Expected: FAIL, module not found.

- [ ] **Step 3: Create the package**

`packages/consent-odrl/package.json`:

```json
{
  "name": "@benefits/consent-odrl",
  "version": "0.1.0",
  "type": "module",
  "main": "dist/index.js",
  "scripts": {
    "build": "tsc -p tsconfig.json",
    "test": "vitest run"
  },
  "dependencies": {
    "@benefits/consent-core": "0.1.0"
  }
}
```

`packages/consent-odrl/tsconfig.json`:

```json
{
  "extends": "../../tsconfig.base.json",
  "compilerOptions": { "outDir": "dist", "rootDir": "src" },
  "include": ["src"]
}
```

- [ ] **Step 4: Write the compiler**

`packages/consent-odrl/src/compile.ts`:

```typescript
import { ConsentGrant } from '@benefits/consent-core';

export const BENEFITS_NS = 'https://w3id.org/benefits/v1/ns/';

export interface CompileOptions {
  releaseRequestId: string;
  quorum: number;
  kAnonymity: number;
  epsilon: number;
  /** The instant the policy is compiled at. Grants already expired are excluded. */
  at: string;
}

interface AtomicConstraint {
  '@type': 'AtomicConstraint';
  leftOperand: string;
  operator: string;
  rightOperand: unknown;
}

function constraint(leftOperand: string, operator: string, rightOperand: unknown): AtomicConstraint {
  return { '@type': 'AtomicConstraint', leftOperand, operator, rightOperand };
}

function unique(values: string[]): string[] {
  const out: string[] = [];
  for (let i = 0; i < values.length; i++) {
    if (out.indexOf(values[i]) === -1) {
      out.push(values[i]);
    }
  }
  return out.sort();
}

export function compilePolicy(all: ConsentGrant[], options: CompileOptions) {
  const grants = all.filter((g) => g.expiresAt === null || g.expiresAt > options.at);

  if (grants.length === 0) {
    throw new Error('cannot compile a policy from an empty consent rule set');
  }

  const recipientClasses = unique(grants.map((g) => g.recipientClass));
  if (recipientClasses.length !== 1) {
    throw new Error(
      'a release policy must target a single recipient class, got: ' + recipientClasses.join(', ')
    );
  }

  return {
    '@context': ['http://www.w3.org/ns/odrl.jsonld', { benefits: BENEFITS_NS }],
    '@type': 'Set',
    permission: [
      {
        action: 'use',
        constraint: [
          constraint('benefits:releaseRequest', 'eq', options.releaseRequestId),
          constraint('benefits:stewardQuorum', 'gteq', options.quorum),
          constraint('benefits:purpose', 'isPartOf', unique(grants.map((g) => g.purpose))),
          constraint('benefits:recipientClass', 'eq', recipientClasses[0]),
          constraint('benefits:fieldSet', 'isPartOf', unique(grants.map((g) => g.field))),
          constraint('benefits:kAnonymity', 'gteq', options.kAnonymity),
          constraint('benefits:epsilonBudget', 'lteq', options.epsilon)
        ]
      }
    ],
    prohibition: [],
    obligation: []
  };
}
```

Expiry is applied here rather than carried into the policy. An ODRL policy has
no notion of the consent expiry that produced it, so a grant that has lapsed
must be excluded at compile time or it silently keeps authorising releases.
This is the compile time counterpart of the runtime expiry check the S1
evaluator performs, and vector v003 covers the S1 side.

`benefits:kAnonymity` and `benefits:epsilonBudget` are emitted now but have no registered function until Phase 3. An unbound left operand is filtered out of scope by EDC rather than failing the policy, which is why Phase 2's gate covers only the consent derived cases.

- [ ] **Step 5: Run to verify it passes**

Run: `cd packages/consent-odrl && npx vitest run`
Expected: PASS, all eleven tests.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: compile a consent rule set into an ODRL policy"
```

---

### Task 10: Steward authorization model and store

**Files:**
- Create: `connector/extensions/benefits-policy/src/main/java/org/benefits/policy/StewardSignature.java`
- Create: `.../StewardAuthorization.java`, `.../StewardAuthorizationStore.java`, `.../InMemoryStewardAuthorizationStore.java`
- Create: `connector/extensions/benefits-policy/src/test/java/org/benefits/policy/InMemoryStewardAuthorizationStoreTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `StewardAuthorizationStore.findByReleaseRequestId(String): StewardAuthorization` returning null when absent, and `StewardAuthorization.distinctStewardCount(): int`, both used by `StewardQuorumFunction` in Task 11.

- [ ] **Step 1: Write the failing test**

`InMemoryStewardAuthorizationStoreTest.java`:

```java
package org.benefits.policy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryStewardAuthorizationStoreTest {

    private final StewardAuthorizationStore store = new InMemoryStewardAuthorizationStore();

    @Test
    void returnsNullWhenAbsent() {
        assertThat(store.findByReleaseRequestId("missing")).isNull();
    }

    @Test
    void roundTripsAnAuthorization() {
        var auth = authorization("rr-1", "s-1", "s-2");
        store.save(auth);
        assertThat(store.findByReleaseRequestId("rr-1")).isEqualTo(auth);
    }

    @Test
    void countsDistinctStewardsNotSignatures() {
        var auth = authorization("rr-2", "s-1", "s-1", "s-2");
        assertThat(auth.distinctStewardCount()).isEqualTo(2);
    }

    @Test
    void countsZeroForNoSignatures() {
        assertThat(authorization("rr-3").distinctStewardCount()).isZero();
    }

    private StewardAuthorization authorization(String releaseRequestId, String... stewardIds) {
        var signatures = java.util.Arrays.stream(stewardIds)
                .map(id -> new StewardSignature(id, "sig-" + id, Instant.parse("2026-08-30T12:00:00Z")))
                .toList();
        return new StewardAuthorization(releaseRequestId, "hash-abc", List.copyOf(signatures),
                Instant.parse("2026-08-30T12:00:00Z"));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :connector:extensions:benefits-policy:test`
Expected: compilation failure, the types do not exist.

- [ ] **Step 3: Write the model**

`StewardSignature.java`:

```java
package org.benefits.policy;

import java.time.Instant;

public record StewardSignature(String stewardId, String signature, Instant signedAt) {
}
```

`StewardAuthorization.java`:

```java
package org.benefits.policy;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

public record StewardAuthorization(
        String releaseRequestId,
        String datasetHash,
        List<StewardSignature> signatures,
        Instant issuedAt) {

    public int distinctStewardCount() {
        return signatures.stream()
                .map(StewardSignature::stewardId)
                .collect(Collectors.toSet())
                .size();
    }
}
```

Counting distinct steward identities rather than signatures is the whole point: a single steward submitting two signatures must not satisfy a quorum of two.

`StewardAuthorizationStore.java`:

```java
package org.benefits.policy;

import org.jetbrains.annotations.Nullable;

public interface StewardAuthorizationStore {

    @Nullable
    StewardAuthorization findByReleaseRequestId(String releaseRequestId);

    void save(StewardAuthorization authorization);
}
```

`InMemoryStewardAuthorizationStore.java`:

```java
package org.benefits.policy;

import org.jetbrains.annotations.Nullable;

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
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :connector:extensions:benefits-policy:test`
Expected: PASS, four tests.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: steward authorization model counting distinct stewards"
```

---

### Task 11: The steward quorum constraint function

**Files:**
- Create: `connector/extensions/benefits-policy/src/main/java/org/benefits/policy/Constraints.java`
- Create: `.../StewardQuorumFunction.java`
- Create: `connector/extensions/benefits-policy/src/test/java/org/benefits/policy/StewardQuorumFunctionTest.java`
- Modify: `.../BenefitsNamespace.java` (add the new keys)

**Interfaces:**
- Consumes: `StewardAuthorizationStore` from Task 10.
- Produces: `StewardQuorumFunction implements AtomicConstraintRuleFunction<Permission, ContractNegotiationPolicyContext>`, registered in Task 12.

- [ ] **Step 1: Add the constraint keys**

Modify `BenefitsNamespace.java` to add, alongside `PROBE_KEY`:

```java
    public static final String RELEASE_REQUEST_KEY = BENEFITS_NAMESPACE + "releaseRequest";
    public static final String STEWARD_QUORUM_KEY = BENEFITS_NAMESPACE + "stewardQuorum";
    public static final String PURPOSE_KEY = BENEFITS_NAMESPACE + "purpose";
    public static final String RECIPIENT_CLASS_KEY = BENEFITS_NAMESPACE + "recipientClass";
    public static final String FIELD_SET_KEY = BENEFITS_NAMESPACE + "fieldSet";
```

- [ ] **Step 2: Write the failing test**

`StewardQuorumFunctionTest.java`:

```java
package org.benefits.policy;

import org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext;
import org.eclipse.edc.participant.spi.ParticipantAgent;
import org.eclipse.edc.policy.model.AtomicConstraint;
import org.eclipse.edc.policy.model.LiteralExpression;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.policy.model.Permission;
import org.eclipse.edc.spi.monitor.ConsoleMonitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.benefits.policy.BenefitsNamespace.RELEASE_REQUEST_KEY;

class StewardQuorumFunctionTest {

    private final StewardAuthorizationStore store = new InMemoryStewardAuthorizationStore();
    private StewardQuorumFunction function;

    @BeforeEach
    void setUp() {
        function = new StewardQuorumFunction(store, new ConsoleMonitor());
    }

    private ContractNegotiationPolicyContext context() {
        return new ContractNegotiationPolicyContext(new ParticipantAgent(Map.of(), Map.of()));
    }

    private Permission permissionWithRelease(String releaseRequestId) {
        return Permission.Builder.newInstance()
                .constraint(AtomicConstraint.Builder.newInstance()
                        .leftExpression(new LiteralExpression(RELEASE_REQUEST_KEY))
                        .operator(Operator.EQ)
                        .rightExpression(new LiteralExpression(releaseRequestId))
                        .build())
                .build();
    }

    private void saveAuthorization(String releaseRequestId, String... stewardIds) {
        var signatures = java.util.Arrays.stream(stewardIds)
                .map(id -> new StewardSignature(id, "sig-" + id, Instant.parse("2026-08-30T12:00:00Z")))
                .toList();
        store.save(new StewardAuthorization(releaseRequestId, "hash", List.copyOf(signatures),
                Instant.parse("2026-08-30T12:00:00Z")));
    }

    @Test
    void allowsWhenQuorumIsMet() {
        saveAuthorization("rr-1", "s-1", "s-2");
        assertThat(function.evaluate(Operator.GEQ, 2, permissionWithRelease("rr-1"), context())).isTrue();
    }

    @Test
    void refusesWhenOnlyOneStewardSigned() {
        saveAuthorization("rr-2", "s-1");
        assertThat(function.evaluate(Operator.GEQ, 2, permissionWithRelease("rr-2"), context())).isFalse();
    }

    @Test
    void refusesWhenOneStewardSignedTwice() {
        saveAuthorization("rr-3", "s-1", "s-1");
        assertThat(function.evaluate(Operator.GEQ, 2, permissionWithRelease("rr-3"), context())).isFalse();
    }

    @Test
    void refusesWhenNoAuthorizationExists() {
        var ctx = context();
        assertThat(function.evaluate(Operator.GEQ, 2, permissionWithRelease("rr-absent"), ctx)).isFalse();
        assertThat(ctx.getProblems()).isNotEmpty();
    }

    @Test
    void refusesWhenTheReleaseRequestSiblingIsMissing() {
        var ctx = context();
        var bare = Permission.Builder.newInstance().build();
        assertThat(function.evaluate(Operator.GEQ, 2, bare, ctx)).isFalse();
        assertThat(ctx.getProblems()).isNotEmpty();
    }

    @Test
    void refusesWhenTheRightOperandIsNotAnInteger() {
        saveAuthorization("rr-4", "s-1", "s-2");
        var ctx = context();
        assertThat(function.evaluate(Operator.GEQ, "two", permissionWithRelease("rr-4"), ctx)).isFalse();
        assertThat(ctx.getProblems()).isNotEmpty();
    }

    @Test
    void refusesAnUnsupportedOperator() {
        saveAuthorization("rr-5", "s-1", "s-2");
        assertThat(function.evaluate(Operator.NEQ, 2, permissionWithRelease("rr-5"), context())).isFalse();
    }
}
```

If `ParticipantAgent`'s constructor signature differs in 0.18.0, adjust the `context()` helper to whatever the class actually requires; the agent is not used by this function and any valid instance works.

- [ ] **Step 3: Run to verify it fails**

Run: `./gradlew :connector:extensions:benefits-policy:test`
Expected: compilation failure.

- [ ] **Step 4: Write the sibling constraint helper**

`Constraints.java`:

```java
package org.benefits.policy;

import org.eclipse.edc.policy.model.AtomicConstraint;
import org.eclipse.edc.policy.model.LiteralExpression;
import org.eclipse.edc.policy.model.Rule;
import org.jetbrains.annotations.Nullable;

public final class Constraints {

    private Constraints() {
    }

    /**
     * EDC's policy context carries no general purpose data map, so a constraint function
     * that needs a value produced by the policy author reads it from a sibling atomic
     * constraint on the same rule.
     */
    @Nullable
    public static String siblingLiteral(Rule rule, String leftOperand) {
        for (var constraint : rule.getConstraints()) {
            if (!(constraint instanceof AtomicConstraint atomic)) {
                continue;
            }
            if (!(atomic.getLeftExpression() instanceof LiteralExpression left)) {
                continue;
            }
            if (!leftOperand.equals(left.asString())) {
                continue;
            }
            if (atomic.getRightExpression() instanceof LiteralExpression right) {
                return right.asString();
            }
        }
        return null;
    }
}
```

- [ ] **Step 5: Write the function**

`StewardQuorumFunction.java`:

```java
package org.benefits.policy;

import org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext;
import org.eclipse.edc.policy.engine.spi.AtomicConstraintRuleFunction;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.policy.model.Permission;
import org.eclipse.edc.spi.monitor.Monitor;

import static org.benefits.policy.BenefitsNamespace.RELEASE_REQUEST_KEY;

public class StewardQuorumFunction implements AtomicConstraintRuleFunction<Permission, ContractNegotiationPolicyContext> {

    private final StewardAuthorizationStore store;
    private final Monitor monitor;

    public StewardQuorumFunction(StewardAuthorizationStore store, Monitor monitor) {
        this.store = store;
        this.monitor = monitor;
    }

    @Override
    public boolean evaluate(Operator operator, Object rightValue, Permission rule, ContractNegotiationPolicyContext context) {
        var required = asInteger(rightValue);
        if (required == null) {
            context.reportProblem("stewardQuorum right operand is not an integer: " + rightValue);
            return false;
        }

        var releaseRequestId = Constraints.siblingLiteral(rule, RELEASE_REQUEST_KEY);
        if (releaseRequestId == null) {
            context.reportProblem("stewardQuorum requires a sibling " + RELEASE_REQUEST_KEY + " constraint");
            return false;
        }

        var authorization = store.findByReleaseRequestId(releaseRequestId);
        if (authorization == null) {
            context.reportProblem("no steward authorization for release request " + releaseRequestId);
            return false;
        }

        var signed = authorization.distinctStewardCount();
        monitor.info("steward quorum check releaseRequest=" + releaseRequestId + " signed=" + signed + " required=" + required);

        return switch (operator) {
            case GEQ -> signed >= required;
            case GT -> signed > required;
            case EQ -> signed == required;
            default -> false;
        };
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

The `String` branch is not defensive padding: JSON-LD expansion frequently delivers numeric right operands as strings, and without it every real negotiation would fail with a confusing type error.

- [ ] **Step 6: Run to verify it passes**

Run: `./gradlew :connector:extensions:benefits-policy:test`
Expected: PASS, seven quorum tests plus the four store tests.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: steward quorum constraint function reading a sibling release request"
```

---

### Task 12: The consent derived constraint functions and full registration

**Files:**
- Create: `.../PurposeFunction.java`, `.../RecipientClassFunction.java`, `.../FieldSetFunction.java`
- Create: `connector/extensions/benefits-policy/src/test/java/org/benefits/policy/ConsentConstraintFunctionsTest.java`
- Modify: `.../BenefitsPolicyExtension.java`

**Interfaces:**
- Consumes: `BenefitsNamespace` keys from Task 11.
- Produces: a fully registered extension exposing `StewardAuthorizationStore` as an EDC service so Task 14 can seed authorizations.

- [ ] **Step 1: Write the failing test**

`ConsentConstraintFunctionsTest.java`:

```java
package org.benefits.policy;

import org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext;
import org.eclipse.edc.participant.spi.ParticipantAgent;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.policy.model.Permission;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConsentConstraintFunctionsTest {

    private ContractNegotiationPolicyContext contextWith(Map<String, Object> claims) {
        return new ContractNegotiationPolicyContext(new ParticipantAgent(claims, Map.of()));
    }

    private final Permission anyRule = Permission.Builder.newInstance().build();

    @Test
    void purposeAllowsWhenTheRequesterPurposeIsInTheGrantedSet() {
        var fn = new PurposeFunction();
        var ctx = contextWith(Map.of("purpose", "service-improvement"));
        assertThat(fn.evaluate(Operator.IN, List.of("service-improvement", "advocacy"), anyRule, ctx)).isTrue();
    }

    @Test
    void purposeRefusesAnUngrantedPurpose() {
        var fn = new PurposeFunction();
        var ctx = contextWith(Map.of("purpose", "marketing"));
        assertThat(fn.evaluate(Operator.IN, List.of("service-improvement"), anyRule, ctx)).isFalse();
    }

    @Test
    void purposeRefusesWhenTheClaimIsAbsent() {
        var fn = new PurposeFunction();
        var ctx = contextWith(Map.of());
        assertThat(fn.evaluate(Operator.IN, List.of("service-improvement"), anyRule, ctx)).isFalse();
        assertThat(ctx.getProblems()).isNotEmpty();
    }

    @Test
    void recipientClassMatchesExactly() {
        var fn = new RecipientClassFunction();
        assertThat(fn.evaluate(Operator.EQ, "statistics", anyRule,
                contextWith(Map.of("recipientClass", "statistics")))).isTrue();
        assertThat(fn.evaluate(Operator.EQ, "statistics", anyRule,
                contextWith(Map.of("recipientClass", "provider")))).isFalse();
    }

    @Test
    void fieldSetRefusesARequestedFieldOutsideTheGrantedSet() {
        var fn = new FieldSetFunction();
        var ctx = contextWith(Map.of("requestedFields", "service.satisfaction,health.condition"));
        assertThat(fn.evaluate(Operator.IN, List.of("service.satisfaction"), anyRule, ctx)).isFalse();
    }

    @Test
    void fieldSetAllowsASubsetOfTheGrantedSet() {
        var fn = new FieldSetFunction();
        var ctx = contextWith(Map.of("requestedFields", "service.satisfaction"));
        assertThat(fn.evaluate(Operator.IN, List.of("service.satisfaction", "service.waitTime"), anyRule, ctx)).isTrue();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :connector:extensions:benefits-policy:test`
Expected: compilation failure.

- [ ] **Step 3: Write the three functions**

`PurposeFunction.java`:

```java
package org.benefits.policy;

import org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext;
import org.eclipse.edc.policy.engine.spi.AtomicConstraintRuleFunction;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.policy.model.Permission;

import java.util.Collection;

public class PurposeFunction implements AtomicConstraintRuleFunction<Permission, ContractNegotiationPolicyContext> {

    @Override
    public boolean evaluate(Operator operator, Object rightValue, Permission rule, ContractNegotiationPolicyContext context) {
        var purpose = context.participantAgent().getClaims().get("purpose");
        if (purpose == null || purpose.toString().isBlank()) {
            context.reportProblem("requester declared no purpose claim");
            return false;
        }
        return switch (operator) {
            case IN -> rightValue instanceof Collection<?> c && c.contains(purpose.toString());
            case EQ -> purpose.equals(rightValue);
            default -> false;
        };
    }
}
```

`RecipientClassFunction.java`:

```java
package org.benefits.policy;

import org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext;
import org.eclipse.edc.policy.engine.spi.AtomicConstraintRuleFunction;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.policy.model.Permission;

import java.util.Collection;

public class RecipientClassFunction implements AtomicConstraintRuleFunction<Permission, ContractNegotiationPolicyContext> {

    @Override
    public boolean evaluate(Operator operator, Object rightValue, Permission rule, ContractNegotiationPolicyContext context) {
        var recipientClass = context.participantAgent().getClaims().get("recipientClass");
        if (recipientClass == null || recipientClass.toString().isBlank()) {
            context.reportProblem("requester declared no recipientClass claim");
            return false;
        }
        return switch (operator) {
            case EQ -> recipientClass.equals(rightValue);
            case IN -> rightValue instanceof Collection<?> c && c.contains(recipientClass.toString());
            default -> false;
        };
    }
}
```

`FieldSetFunction.java`:

```java
package org.benefits.policy;

import org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext;
import org.eclipse.edc.policy.engine.spi.AtomicConstraintRuleFunction;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.policy.model.Permission;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public class FieldSetFunction implements AtomicConstraintRuleFunction<Permission, ContractNegotiationPolicyContext> {

    @Override
    public boolean evaluate(Operator operator, Object rightValue, Permission rule, ContractNegotiationPolicyContext context) {
        var claim = context.participantAgent().getClaims().get("requestedFields");
        if (claim == null || claim.toString().isBlank()) {
            context.reportProblem("requester declared no requestedFields claim");
            return false;
        }
        List<String> requested = Arrays.stream(claim.toString().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        if (operator != Operator.IN) {
            return false;
        }
        if (!(rightValue instanceof Collection<?> granted)) {
            return false;
        }
        return granted.containsAll(requested);
    }
}
```

The direction matters and is easy to invert: the granted set must contain every requested field, not the other way round.

- [ ] **Step 4: Rewrite the registration extension**

Replace the body of `BenefitsPolicyExtension.java`:

```java
package org.benefits.policy;

import org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext;
import org.eclipse.edc.policy.engine.spi.PolicyEngine;
import org.eclipse.edc.policy.engine.spi.RuleBindingRegistry;
import org.eclipse.edc.policy.model.Permission;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Provider;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

import static org.benefits.policy.BenefitsNamespace.FIELD_SET_KEY;
import static org.benefits.policy.BenefitsNamespace.PROBE_KEY;
import static org.benefits.policy.BenefitsNamespace.PURPOSE_KEY;
import static org.benefits.policy.BenefitsNamespace.RECIPIENT_CLASS_KEY;
import static org.benefits.policy.BenefitsNamespace.RELEASE_REQUEST_KEY;
import static org.benefits.policy.BenefitsNamespace.STEWARD_QUORUM_KEY;
import static org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext.NEGOTIATION_SCOPE;
import static org.eclipse.edc.jsonld.spi.PropertyAndTypeNames.ODRL_USE_ACTION_ATTRIBUTE;
import static org.eclipse.edc.policy.engine.spi.PolicyEngine.ALL_SCOPES;

public class BenefitsPolicyExtension implements ServiceExtension {

    @Inject
    private RuleBindingRegistry ruleBindingRegistry;
    @Inject
    private PolicyEngine policyEngine;

    private final StewardAuthorizationStore store = new InMemoryStewardAuthorizationStore();

    @Override
    public String name() {
        return "BENEFITS policy functions";
    }

    @Provider
    public StewardAuthorizationStore stewardAuthorizationStore() {
        return store;
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        var monitor = context.getMonitor();

        ruleBindingRegistry.bind(ODRL_USE_ACTION_ATTRIBUTE, ALL_SCOPES);
        for (var key : new String[]{PROBE_KEY, RELEASE_REQUEST_KEY, STEWARD_QUORUM_KEY, PURPOSE_KEY, RECIPIENT_CLASS_KEY, FIELD_SET_KEY}) {
            ruleBindingRegistry.bind(key, NEGOTIATION_SCOPE);
        }

        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class, PROBE_KEY,
                new ProbeConstraintFunction(monitor));
        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class, STEWARD_QUORUM_KEY,
                new StewardQuorumFunction(store, monitor));
        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class, PURPOSE_KEY,
                new PurposeFunction());
        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class, RECIPIENT_CLASS_KEY,
                new RecipientClassFunction());
        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class, FIELD_SET_KEY,
                new FieldSetFunction());

        monitor.info("BENEFITS policy functions registered");
    }
}
```

`RELEASE_REQUEST_KEY` is bound to the scope but has no registered function on purpose. Binding keeps it from being filtered out of the rule, which is what makes it visible to `Constraints.siblingLiteral`. Verify this in Task 14; if EDC drops constraints with no function despite the binding, move the release request id into the `benefits:stewardQuorum` right operand as an object of the form `{"quorum": 2, "releaseRequest": "rr-42"}` and adjust `StewardQuorumFunction.evaluate` to read both from `rightValue`.

- [ ] **Step 5: Add the requestedFields claim to the mock IAM**

In `BenefitsIamExtension`, replace the token map in `obtainClientCredentials` with:

```java
            var token = Map.of(
                    "client_id", contextConfig.getString(participantContextId, "edc.participant.id"),
                    "purpose", contextConfig.getString(participantContextId, "benefits.requester.purpose", ""),
                    "recipientClass", contextConfig.getString(participantContextId, "benefits.requester.recipientClass", ""),
                    "requestedFields", contextConfig.getString(participantContextId, "benefits.requester.fields", "")
            );
```

and the claim token in `verifyJwtToken` with:

```java
            return Result.success(ClaimToken.Builder.newInstance()
                    .claim("client_id", token.get("client_id"))
                    .claim("purpose", token.get("purpose"))
                    .claim("recipientClass", token.get("recipientClass"))
                    .claim("requestedFields", token.get("requestedFields"))
                    .build());
```

Add to `connector/runtime/consumer.properties`:

```properties
benefits.requester.fields=service.satisfaction
```

- [ ] **Step 6: Run to verify it passes**

Run: `./gradlew :connector:extensions:benefits-policy:test && ./gradlew shadowJar`
Expected: PASS, all tests, and the shadow jar builds.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: consent derived constraint functions and full policy registration"
```

---

### Task 13: The Java conformance runner

This is what pays for the two language boundary. The same vectors, decided by Java.

**Files:**
- Create: `connector/extensions/benefits-policy/src/main/java/org/benefits/policy/ConsentEvaluator.java`
- Create: `connector/extensions/benefits-policy/src/test/java/org/benefits/policy/ConformanceVectorTest.java`
- Modify: `connector/extensions/benefits-policy/build.gradle.kts` (add a Jackson test dependency if not already present transitively)

**Interfaces:**
- Consumes: `conformance/vectors/*.json`.
- Produces: `ConsentEvaluator.evaluate(JsonNode record, String nowIso): String` returning a reason code from the closed set in `conformance/README.md`.

- [ ] **Step 1: Write the failing test**

`ConformanceVectorTest.java`:

```java
package org.benefits.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ConformanceVectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path vectorDir() {
        return Path.of(System.getProperty("user.dir")).resolve("../../../conformance/vectors").normalize();
    }

    @TestFactory
    Stream<DynamicTest> everyVectorIsDecidedIdentically() throws IOException {
        try (var files = Files.list(vectorDir())) {
            List<Path> paths = files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            assertThat(paths).isNotEmpty();
            return paths.stream().map(path -> DynamicTest.dynamicTest(path.getFileName().toString(), () -> {
                JsonNode vector = MAPPER.readTree(path.toFile());
                String reason = ConsentEvaluator.evaluate(vector.get("record"), vector.get("now").asText());
                assertThat(reason).isEqualTo(vector.get("expected").get("reason").asText());
            }));
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :connector:extensions:benefits-policy:test --tests '*ConformanceVectorTest*'`
Expected: compilation failure.

If the path resolution is wrong, print `vectorDir()` and adjust the relative segments; Gradle sets `user.dir` to the subproject directory.

- [ ] **Step 3: Write the Java evaluator**

`ConsentEvaluator.java`. Deliberately a direct transcription of `evaluate.ts`, including the field sort, so that divergence is visible as a structural difference rather than hidden in style.

```java
package org.benefits.policy;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class ConsentEvaluator {

    private ConsentEvaluator() {
    }

    public static String evaluate(JsonNode record, String nowIso) {
        if (record == null || !record.hasNonNull("type") || !"contribution".equals(record.get("type").asText())) {
            return "not-a-contribution";
        }

        JsonNode consent = record.get("consent");
        if (consent == null || consent.isNull() || !consent.has("grants")) {
            return "missing-consent-assertion";
        }

        if (!consent.path("participantRef").asText().equals(record.path("participantRef").asText())) {
            return "participant-mismatch";
        }

        JsonNode fields = record.get("fields");
        List<String> names = new ArrayList<>();
        if (fields != null) {
            for (Iterator<String> it = fields.fieldNames(); it.hasNext(); ) {
                names.add(it.next());
            }
        }
        names.sort(String::compareTo);

        String purpose = record.path("declaredPurpose").asText();
        String recipientClass = record.path("declaredRecipientClass").asText();

        for (String name : names) {
            if (!hasGrant(consent.get("grants"), name, purpose, recipientClass, nowIso)) {
                return "no-grant:" + name;
            }
        }

        return "ok";
    }

    private static boolean hasGrant(JsonNode grants, String field, String purpose, String recipientClass, String nowIso) {
        if (grants == null || !grants.isArray()) {
            return false;
        }
        for (JsonNode g : grants) {
            if (!field.equals(g.path("field").asText())) {
                continue;
            }
            if (!purpose.equals(g.path("purpose").asText())) {
                continue;
            }
            if (!recipientClass.equals(g.path("recipientClass").asText())) {
                continue;
            }
            if (g.path("grantedAt").asText().compareTo(nowIso) > 0) {
                continue;
            }
            JsonNode expiresAt = g.get("expiresAt");
            if (expiresAt != null && !expiresAt.isNull() && expiresAt.asText().compareTo(nowIso) <= 0) {
                continue;
            }
            return true;
        }
        return false;
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :connector:extensions:benefits-policy:test --tests '*ConformanceVectorTest*'`
Expected: PASS, eight dynamic tests.

If Jackson does not resolve, add `testImplementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")` to the extension's build file.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "test: Java consent evaluator agrees with TypeScript on all conformance vectors"
```

---

### Task 14: The C1 consent case suite end to end

This is the Phase 2 gate. Every consent derived violation must be refused during a real contract negotiation, not in a unit test.

**Files:**
- Modify: `.../BenefitsPolicyExtension.java` (seed authorizations from config)
- Modify: `connector/extensions/benefits-policy/build.gradle.kts` (Jackson JSR-310 module)
- Create: `tools/compile-policy.mjs`, `tools/contract-request.py`
- Create: `tools/lib.sh`, `tools/run-case.sh`, `tools/violations.sh`
- Create: `docs/results/c1-consent-cases.md` (generated)

**Interfaces:**
- Consumes: everything from Tasks 1 through 13.
- Produces: `docs/results/c1-consent-cases.md`, the table that goes into the manuscript.

- [ ] **Step 1: Seed steward authorizations from a config file**

The steward portal does not exist until Phase 6, so Phase 2 seeds authorizations at startup. A file plus a restart is sufficient because every C1 case is a different seed anyway, and it avoids building a management API that Phase 6 will replace.

Add to `connector/extensions/benefits-policy/build.gradle.kts`:

```kotlin
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.2")
```

Add to `BenefitsPolicyExtension`, and call `seed(context);` as the last line of `initialize`:

```java
    private void seed(ServiceExtensionContext context) {
        var path = context.getSetting("benefits.seed.authorizations", "");
        if (path.isBlank()) {
            return;
        }
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        try {
            var type = mapper.getTypeFactory().constructCollectionType(java.util.List.class, StewardAuthorization.class);
            java.util.List<StewardAuthorization> authorizations =
                    mapper.readValue(java.nio.file.Files.readString(java.nio.file.Path.of(path)), type);
            authorizations.forEach(store::save);
            context.getMonitor().info("seeded " + authorizations.size() + " steward authorizations from " + path);
        } catch (java.io.IOException e) {
            throw new org.eclipse.edc.spi.EdcException("failed to seed steward authorizations from " + path, e);
        }
    }
```

Add the import `org.eclipse.edc.spi.system.ServiceExtensionContext` if it is not already present.

- [ ] **Step 2: Write the policy compiler driver**

`tools/compile-policy.mjs` uses the real `compilePolicy` from Task 9, so the suite exercises the compiler rather than hand written JSON.

```javascript
import { compilePolicy } from '../packages/consent-odrl/dist/compile.js';

const AT = '2026-08-30T12:00:00.000Z';

function grant(field, expiresAt = null) {
  return {
    field,
    purpose: 'service-improvement',
    recipientClass: 'statistics',
    grantedAt: '2026-08-01T00:00:00.000Z',
    expiresAt
  };
}

const CASES = {
  'quorum-met': [grant('service.satisfaction')],
  'quorum-missing': [grant('service.satisfaction')],
  'quorum-one-steward-twice': [grant('service.satisfaction')],
  'purpose-mismatch': [grant('service.satisfaction')],
  'recipient-class-mismatch': [grant('service.satisfaction')],
  'field-outside-grant': [grant('service.satisfaction')],
  'expired-grant-field-excluded': [
    grant('service.satisfaction'),
    grant('health.condition', '2026-08-15T00:00:00.000Z')
  ]
};

const name = process.argv[2];
const grants = CASES[name];
if (!grants) {
  console.error('unknown case: ' + name);
  process.exit(2);
}

const policy = compilePolicy(grants, {
  releaseRequestId: 'rr-' + name,
  quorum: 2,
  kAnonymity: 5,
  epsilon: 0.5,
  at: AT
});

process.stdout.write(JSON.stringify({
  '@context': { '@vocab': 'https://w3id.org/edc/v0.0.1/ns/' },
  '@id': 'benefits-policy',
  policy
}, null, 2));
```

- [ ] **Step 3: Write the contract request builder**

`tools/contract-request.py` builds the request from whatever offer the catalog actually returned, so the request never drifts from the published policy.

```python
import json
import sys

catalog = json.load(open('/tmp/catalog.json'))

def find_offer(node):
    if isinstance(node, dict):
        if node.get('@type') in ('odrl:Offer', 'Offer') and '@id' in node:
            return node
        for value in node.values():
            found = find_offer(value)
            if found is not None:
                return found
    elif isinstance(node, list):
        for value in node:
            found = find_offer(value)
            if found is not None:
                return found
    return None

offer = find_offer(catalog)
if offer is None:
    sys.stderr.write('no offer in catalog\n')
    sys.exit(1)

offer = dict(offer)
offer['assigner'] = 'provider'
offer['target'] = 'benefits-aggregate'

json.dump({
    '@context': {'@vocab': 'https://w3id.org/edc/v0.0.1/ns/'},
    '@type': 'ContractRequest',
    'counterPartyId': 'provider',
    'counterPartyAddress': 'http://localhost:19194/protocol/2025-1',
    'protocol': 'dataspace-protocol-http:2025-1',
    'policy': offer
}, sys.stdout, indent=2)
```

- [ ] **Step 4: Write the shared driver library**

`tools/lib.sh`:

```bash
# shellcheck shell=bash
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$ROOT/connector/runtime/build/libs/connector.jar"
PROVIDER_MGMT=http://localhost:19193/management
CONSUMER_MGMT=http://localhost:29193/management
KEY="x-api-key: password"
JSON="content-type: application/json"

stop_all() {
  for pidfile in /tmp/provider.pid /tmp/consumer.pid; do
    [ -f "$pidfile" ] && kill "$(cat "$pidfile")" 2>/dev/null || true
    rm -f "$pidfile"
  done
  sleep 1
}

start_connector() {
  local name=$1 config=$2 mgmt_port=$3
  shift 3
  java "$@" -Dedc.fs.config="$config" -jar "$JAR" > "/tmp/$name.log" 2>&1 &
  echo $! > "/tmp/$name.pid"
  for _ in $(seq 1 60); do
    if curl -sf -H "$KEY" -H "$JSON" -X POST \
         "http://localhost:$mgmt_port/management/v3/assets/request" -d '{}' >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "$name did not become ready" >&2
  tail -40 "/tmp/$name.log" >&2
  return 1
}

publish_offer() {
  local policy_file=$1
  curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/assets" -d '{
    "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
    "@id": "benefits-aggregate",
    "properties": { "name": "aggregate release", "contenttype": "application/json" },
    "dataAddress": { "type": "test" }
  }' >/dev/null
  curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/policydefinitions" -d @"$policy_file" >/dev/null
  curl -sf -H "$KEY" -H "$JSON" -X POST "$PROVIDER_MGMT/v3/contractdefinitions" -d '{
    "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
    "@id": "benefits-definition",
    "accessPolicyId": "benefits-policy",
    "contractPolicyId": "benefits-policy",
    "assetsSelector": [{
      "@type": "CriterionDto",
      "operandLeft": "https://w3id.org/edc/v0.0.1/ns/id",
      "operator": "=",
      "operandRight": "benefits-aggregate"
    }]
  }' >/dev/null
}

negotiate() {
  curl -sf -H "$KEY" -H "$JSON" -X POST "$CONSUMER_MGMT/v3/catalogs/request" -d '{
    "@context": { "@vocab": "https://w3id.org/edc/v0.0.1/ns/" },
    "counterPartyAddress": "http://localhost:19194/protocol/2025-1",
    "counterPartyId": "provider",
    "protocol": "dataspace-protocol-http:2025-1"
  }' > /tmp/catalog.json

  if ! python3 "$ROOT/tools/contract-request.py" > /tmp/contract-request.json 2>/tmp/contract-request.err; then
    echo "NO_OFFER"
    return 0
  fi

  local neg neg_id state=""
  neg=$(curl -sf -H "$KEY" -H "$JSON" -X POST "$CONSUMER_MGMT/v3/contractnegotiations" -d @/tmp/contract-request.json)
  neg_id=$(echo "$neg" | python3 -c 'import json,sys; print(json.load(sys.stdin)["@id"])')

  for _ in $(seq 1 60); do
    state=$(curl -sf -H "$KEY" "$CONSUMER_MGMT/v3/contractnegotiations/$neg_id" \
            | python3 -c 'import json,sys; print(json.load(sys.stdin).get("state",""))')
    case "$state" in FINALIZED|TERMINATED) break ;; esac
    sleep 1
  done
  echo "$state"
}
```

A case whose offer never reaches the catalog prints `NO_OFFER` rather than crashing. That is a distinct outcome from `TERMINATED` and the table must show it, because an offer filtered out at catalog time is a different enforcement point from one refused at negotiation.

- [ ] **Step 5: Write the per case driver**

`tools/run-case.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib.sh"

CASE=$1
WORK=/tmp/benefits-case
rm -rf "$WORK" && mkdir -p "$WORK"

PURPOSE=service-improvement
RECIPIENT=statistics
FIELDS=service.satisfaction
SEED=""

case "$CASE" in
  quorum-met)                   SEED="s-1 s-2" ;;
  quorum-missing)               SEED="" ;;
  quorum-one-steward-twice)     SEED="s-1 s-1" ;;
  purpose-mismatch)             SEED="s-1 s-2"; PURPOSE=marketing ;;
  recipient-class-mismatch)     SEED="s-1 s-2"; RECIPIENT=provider ;;
  field-outside-grant)          SEED="s-1 s-2"; FIELDS="service.satisfaction,health.condition" ;;
  expired-grant-field-excluded) SEED="s-1 s-2"; FIELDS="health.condition" ;;
  *) echo "unknown case: $CASE" >&2; exit 2 ;;
esac

node "$ROOT/tools/compile-policy.mjs" "$CASE" > "$WORK/policy.json"

SEED_ARG=()
if [ -n "$SEED" ]; then
  python3 - "$CASE" "$WORK/seed.json" $SEED <<'PY'
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
    ]
}], open(out, "w"))
PY
  SEED_ARG=(-Dbenefits.seed.authorizations="$WORK/seed.json")
fi

sed -e "s|^benefits.requester.purpose=.*|benefits.requester.purpose=$PURPOSE|" \
    -e "s|^benefits.requester.recipientClass=.*|benefits.requester.recipientClass=$RECIPIENT|" \
    -e "s|^benefits.requester.fields=.*|benefits.requester.fields=$FIELDS|" \
    "$ROOT/connector/runtime/consumer.properties" > "$WORK/consumer.properties"

stop_all
start_connector provider "$ROOT/connector/runtime/provider.properties" 19193 "${SEED_ARG[@]}"
start_connector consumer "$WORK/consumer.properties" 29193
publish_offer "$WORK/policy.json"
negotiate
stop_all
```

Java system properties set with `-D` are read by `context.getSetting`, so the seed path needs no config file edit.

- [ ] **Step 6: Write the suite runner**

`tools/violations.sh`:

```bash
#!/usr/bin/env bash
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/docs/results/c1-consent-cases.md"

CASES=(
  "quorum-met:FINALIZED"
  "quorum-missing:TERMINATED"
  "quorum-one-steward-twice:TERMINATED"
  "purpose-mismatch:TERMINATED"
  "recipient-class-mismatch:TERMINATED"
  "field-outside-grant:TERMINATED"
  "expired-grant-field-excluded:TERMINATED"
)

mkdir -p "$(dirname "$OUT")"
{
  echo "# C1 enforcement correctness, consent cases"
  echo
  echo "Generated by tools/violations.sh. Every row is a real contract negotiation"
  echo "against the connector, not a unit test."
  echo
  echo "| Case | Expected | Observed | Pass |"
  echo "|---|---|---|---|"
} > "$OUT"

failed=0
for entry in "${CASES[@]}"; do
  name=${entry%%:*}
  expected=${entry##*:}
  observed=$("$ROOT/tools/run-case.sh" "$name" | tail -1)
  cp /tmp/provider.log "/tmp/provider-$name.log" 2>/dev/null || true
  if [ "$observed" = "$expected" ]; then pass=yes; else pass=no; failed=1; fi
  echo "| $name | $expected | $observed | $pass |" >> "$OUT"
done

cat "$OUT"
exit $failed
```

- [ ] **Step 7: Build everything and run the suite**

```bash
cd /home/und3rd06012/benefits
npm run build --workspaces --if-present
./gradlew shadowJar
chmod +x tools/run-case.sh tools/violations.sh
./tools/violations.sh
```

Expected: seven rows, every `Pass` column reading `yes`, and exit status 0.

`quorum-met` is in the table deliberately. A suite that only ever expects `TERMINATED` would pass just as well against a connector that refuses everything.

- [ ] **Step 8: Verify each refusal has the right cause**

A negotiation can terminate for many reasons, and a suite that does not check why is measuring nothing. Each case keeps its own provider log, so check the specific problem:

```bash
grep -l "no steward authorization for release request rr-quorum-missing" /tmp/provider-quorum-missing.log
grep -l "requester declared no purpose\|steward quorum check" /tmp/provider-purpose-mismatch.log
grep -c "steward quorum check releaseRequest=rr-quorum-met signed=2 required=2" /tmp/provider-quorum-met.log
```

Expected: the first two commands print the filename, and the third prints 1 or more.

For `purpose-mismatch` the purpose claim is present but wrong, so the refusal is a plain `false` from `PurposeFunction` with no reported problem. Confirm instead that the quorum check succeeded and the negotiation still terminated, which isolates the purpose function as the cause:

```bash
grep -c "steward quorum check releaseRequest=rr-purpose-mismatch signed=2 required=2" /tmp/provider-purpose-mismatch.log
```

Expected: 1 or more, alongside a `TERMINATED` outcome in the table.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "test: C1 consent cases refused during real contract negotiation"
```

---

## Phase Gates

Do not start the next phase until its predecessor's gate passes.

| Phase | Gate | Verified by |
|---|---|---|
| 0 | Runtime boots, probe function observed evaluating during a real negotiation, CouchDB pinned and engine capabilities recorded | Tasks 1, 2, 3 |
| 1 | Real CouchDB decides every conformance vector identically to the TypeScript evaluator, and refuses a stripped consent assertion | Task 8 |
| 2 | Every consent derived violation is refused during a real contract negotiation, and Java agrees with TypeScript on every vector | Tasks 13, 14 |

If the Task 2 gate fails and cannot be recovered within a day of work, stop and escalate rather than working around it. That gate failing means approach C does not hold, and the spec's Approach A is the fallback. Recording why it failed is more valuable than a workaround.

## What this plan does not cover

Phases 3 through 5 (privacy pipeline, audit chain, device fleet) are Plan 2, written after the Phase 2 gate passes. Phases 6 and 7 (user interfaces, experiments) are Plan 3. Phase 8 (the manuscript) is driven by the ARS academic-paper skills.

Experiments E1 through E4, C1's privacy cases, G1, and G2 are all out of scope here. The only experimental artifact this plan produces is `docs/results/c1-consent-cases.md`.
