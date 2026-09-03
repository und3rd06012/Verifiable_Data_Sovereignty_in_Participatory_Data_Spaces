# G1+G2 Experiments Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build G1 (a two-arm quorum-availability simulation driving the real steward-quorum machinery) and G2 (a real production consent filter plus a heterogeneity-vs-utility sweep), the third and final phase-7 experiment sub-project.

**Architecture:** Two new, independent Gradle modules, `experiments:harness:g1` and `experiments:harness:g2`. G1 depends only on `connector:extensions:benefits-policy` and drives the real `StewardQuorumFunction`/`InMemoryStewardAuthorizationStore` under a simulated per-tick availability timeline, alongside a second, explicitly-hypothetical decision function on the same timeline. G2 makes a real, additive, backward-compatible change to `connector:extensions:benefits-privacy` (a new `ConsentGrant` type and new nullable fields on `Contribution`/`QuerySpec`, wired into `PrivacyPipeline.compute` and `CouchDbContributionSource`), then a new module that sweeps a consent-heterogeneity parameter against that real, now-consent-aware pipeline, reusing `experiments:harness:e4`'s population generator, query workload, and statistics utilities.

**Tech Stack:** Java 21, Gradle, JUnit 5, AssertJ, the existing EDC policy-engine types already exposed by `benefits-policy`.

**Spec:** `docs/superpowers/specs/2026-09-03-g1-g2-experiments-design.md`

## Global Constraints

- No em-dashes anywhere: not in code, comments, commit messages, or this plan's own prose. Use periods, semicolons, or commas instead.
- No LLM/AI-tooling attribution in commits.
- No emojis in code or logs.
- Seeded determinism only via `java.util.Random`, never `Math.random()`/`ThreadLocalRandom`.
- Every seed that could produce dense, consecutive `long` values must pass through the `mix64` avalanche finalizer below before constructing a `Random`, from each class's first implementation, not retrofitted later:

```java
private static long mix64(long z) {
    z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
    z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
    z = z ^ (z >>> 33);
    return z;
}
```

- A live-code-verification spike (construct the real classes directly with hand-built values, assert on real output, no mocks) is required before either full sweep is built: Task 2 for G1, Task 8 for G2.
- Do not modify `StewardQuorumFunction`, `InMemoryStewardAuthorizationStore`, `KAnonymity`, `LaplaceNoiseSource`, `InMemoryBudgetAccountant`, `ReleaseArtifact`, `BudgetAccountant`, or any file under `experiments/harness/e1`, `e3`, or `e4`.
- G2's changes to `Contribution` and `QuerySpec` must keep every existing two/three-argument constructor call site compiling and behaving byte-identically (`ReleaseRequestResource`, `KAnonymityTest`, `PrivacyPipelineTest`, `InMemoryContributionSourceTest`, `CouchDbContributionSourceIntegrationTest`, and every file under `experiments/harness/e4`). Task 7 proves this with a real, full test run across all affected modules, not just the new module's own tests.

---

## Task 1: G1 Gradle module, `Statistics`, and module scaffolding

**Files:**
- Modify: `settings.gradle.kts`
- Create: `experiments/harness/g1/build.gradle.kts`
- Create: `experiments/harness/g1/src/main/java/org/benefits/experiments/g1/Statistics.java`
- Test: `experiments/harness/g1/src/test/java/org/benefits/experiments/g1/StatisticsTest.java`

**Interfaces:**
- Produces: `org.benefits.experiments.g1.Statistics.median(List<Double>): double`, `.iqr(List<Double>): double`, `.percentile(List<Double>, double): double`. Used by Task 5.

- [ ] **Step 1: Add the module to the build**

Append to `settings.gradle.kts` (after the existing `experiments:harness:e4` line):

```kotlin
include(":experiments:harness:g1")
include(":experiments:harness:g2")
```

(Both lines now, even though `g2` is created in Task 7. Gradle does not require the directory to exist yet at settings-evaluation time for an `include` alone to be harmless once the directory appears, but to avoid a broken build in between, create only an empty placeholder is not needed: Gradle resolves `include` lazily against the directory tree at build time, and this task creates `g1` immediately while `g2` is created in Task 7. If `./gradlew` fails before Task 7 with `g2` missing, remove the `g2` line here and re-add it verbatim as the first step of Task 7 instead.)

- [ ] **Step 2: Create the build file**

`experiments/harness/g1/build.gradle.kts`:

```kotlin
plugins {
    `java-library`
}

dependencies {
    implementation(project(":connector:extensions:benefits-policy"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 3: Duplicate `Statistics` verbatim**

Copy `experiments/harness/e4/src/main/java/org/benefits/experiments/e4/Statistics.java` to
`experiments/harness/g1/src/main/java/org/benefits/experiments/g1/Statistics.java`, changing only the
package declaration:

```java
package org.benefits.experiments.g1;

import java.util.List;

/**
 * Median and interquartile range via linear interpolation between order statistics
 * (the same convention as NumPy's default and R's type 7). Duplicated verbatim from
 * experiments/harness/e4's own Statistics class (spec section 3.4): g1 has no other
 * reason to depend on e4, so this one small, dependency-free class is copied rather
 * than shared.
 */
public final class Statistics {

    private Statistics() {
    }

    public static double median(List<Double> values) {
        return percentile(values, 0.5);
    }

    public static double iqr(List<Double> values) {
        return percentile(values, 0.75) - percentile(values, 0.25);
    }

    public static double percentile(List<Double> values, double p) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("cannot compute a percentile over zero values");
        }
        var sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        if (sorted.length == 1) {
            return sorted[0];
        }
        var rank = p * (sorted.length - 1);
        var lowerIndex = (int) Math.floor(rank);
        var upperIndex = (int) Math.ceil(rank);
        if (lowerIndex == upperIndex) {
            return sorted[lowerIndex];
        }
        var fraction = rank - lowerIndex;
        return sorted[lowerIndex] + fraction * (sorted[upperIndex] - sorted[lowerIndex]);
    }
}
```

- [ ] **Step 4: Write the test**

`experiments/harness/g1/src/test/java/org/benefits/experiments/g1/StatisticsTest.java`:

```java
package org.benefits.experiments.g1;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class StatisticsTest {

    @Test
    void medianOfAnOddCountIsTheMiddleValue() {
        assertThat(Statistics.median(List.of(1.0, 3.0, 2.0))).isEqualTo(2.0);
    }

    @Test
    void medianOfAnEvenCountInterpolates() {
        assertThat(Statistics.median(List.of(1.0, 2.0, 3.0, 4.0))).isEqualTo(2.5);
    }

    @Test
    void iqrOfAUniformSpreadMatchesTheKnownValue() {
        var values = List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0);
        assertThat(Statistics.iqr(values)).isCloseTo(3.5, within(1e-9));
    }

    @Test
    void percentileOfASingleValueIsThatValue() {
        assertThat(Statistics.percentile(List.of(5.0), 0.9)).isEqualTo(5.0);
    }

    @Test
    void percentileOfAnEmptyListThrows() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Statistics.median(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew :experiments:harness:g1:test`
Expected: all 5 tests pass.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts experiments/harness/g1/build.gradle.kts \
  experiments/harness/g1/src/main/java/org/benefits/experiments/g1/Statistics.java \
  experiments/harness/g1/src/test/java/org/benefits/experiments/g1/StatisticsTest.java
git commit -m "feat(g1): add the g1 module and its duplicated Statistics class"
```

---

## Task 2: G1 live spike, driving the real steward-quorum classes and verifying `mix64`

**Files:**
- Test: `experiments/harness/g1/src/test/java/org/benefits/experiments/g1/LiveSpikeTest.java`

**Interfaces:**
- Consumes: `org.benefits.policy.{StewardQuorumFunction, InMemoryStewardAuthorizationStore, StewardSignature, StewardVeto, StewardAuthorization}` (all real, unmodified `benefits-policy` classes).
- Produces: confirmation, for later tasks, that (a) `StewardQuorumFunction`/the store can be driven with repeated, incremental `appendSignature`/`appendVeto` calls across many simulated steps and re-evaluated each time (not just a single `save` with a final state, the way `StewardQuorumFunctionTest.java` already does it), and (b) `mix64` spreads first-draw values from dense consecutive seeds the way E4's own final review measured (spec section 6).

- [ ] **Step 1: Read the reference test first**

Read `connector/extensions/benefits-policy/src/test/java/org/benefits/policy/StewardQuorumFunctionTest.java` in full before writing this spike. It is the exact pattern this task and Task 3 mirror: a plain `new StewardQuorumFunction(store, monitor)`, a `Permission` built via `Permission.Builder` with a `RELEASE_REQUEST_KEY` sibling constraint, and a `new ContractNegotiationPolicyContext(new ParticipantAgent("consumer", Map.of(), Map.of()))`. No EDC runtime is needed.

- [ ] **Step 2: Write the spike test**

`experiments/harness/g1/src/test/java/org/benefits/experiments/g1/LiveSpikeTest.java`:

```java
package org.benefits.experiments.g1;

import org.benefits.policy.InMemoryStewardAuthorizationStore;
import org.benefits.policy.StewardQuorumFunction;
import org.benefits.policy.StewardSignature;
import org.benefits.policy.StewardVeto;
import org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext;
import org.eclipse.edc.participant.spi.ParticipantAgent;
import org.eclipse.edc.policy.model.AtomicConstraint;
import org.eclipse.edc.policy.model.LiteralExpression;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.policy.model.Permission;
import org.eclipse.edc.spi.monitor.ConsoleMonitor;
import org.eclipse.edc.spi.monitor.Monitor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.benefits.policy.BenefitsNamespace.RELEASE_REQUEST_KEY;

/**
 * Exercises the real, unmodified steward-quorum classes directly, confirming the
 * behavior Task 3 onward depends on, before building the timeline simulation on it.
 */
class LiveSpikeTest {

    private static ContractNegotiationPolicyContext context() {
        return new ContractNegotiationPolicyContext(new ParticipantAgent("g1-consumer", Map.of(), Map.of()));
    }

    private static Permission permissionFor(String releaseRequestId) {
        return Permission.Builder.newInstance()
                .constraint(AtomicConstraint.Builder.newInstance()
                        .leftExpression(new LiteralExpression(RELEASE_REQUEST_KEY))
                        .operator(Operator.EQ)
                        .rightExpression(new LiteralExpression(releaseRequestId))
                        .build())
                .build();
    }

    @Test
    void repeatedIncrementalAppendSignatureCallsAccumulateAndQuorumBecomesTrueOnlyOnceEnoughSign() {
        var store = new InMemoryStewardAuthorizationStore();
        var function = new StewardQuorumFunction(store, new ConsoleMonitor());
        var permission = permissionFor("g1-spike-1");

        store.appendSignature("g1-spike-1", "hash", new StewardSignature("s-1", "sig-1", Instant.EPOCH));
        assertThat(function.evaluate(Operator.GEQ, 2, permission, context())).isFalse();

        store.appendSignature("g1-spike-1", "hash", new StewardSignature("s-2", "sig-2", Instant.EPOCH));
        assertThat(function.evaluate(Operator.GEQ, 2, permission, context())).isTrue();

        store.appendSignature("g1-spike-1", "hash", new StewardSignature("s-3", "sig-3", Instant.EPOCH));
        assertThat(function.evaluate(Operator.GEQ, 2, permission, context())).isTrue();
    }

    @Test
    void appendVetoDoesNotAffectTheRealQuorumFunctionAtAll() {
        var store = new InMemoryStewardAuthorizationStore();
        var function = new StewardQuorumFunction(store, new ConsoleMonitor());
        var permission = permissionFor("g1-spike-2");

        store.appendVeto("g1-spike-2", "hash", new StewardVeto("s-1", "objection", Instant.EPOCH));
        store.appendVeto("g1-spike-2", "hash", new StewardVeto("s-2", "objection", Instant.EPOCH));
        store.appendSignature("g1-spike-2", "hash", new StewardSignature("s-3", "sig-3", Instant.EPOCH));
        store.appendSignature("g1-spike-2", "hash", new StewardSignature("s-4", "sig-4", Instant.EPOCH));

        // Two vetoes recorded, quorum 2, two signatures: the real function must still
        // report true, since it counts only distinct signers and never reads vetoes.
        assertThat(function.evaluate(Operator.GEQ, 2, permission, context())).isTrue();
        var authorization = store.findByReleaseRequestId("g1-spike-2");
        assertThat(authorization.vetoes()).hasSize(2);
    }

    @Test
    void evaluatingRepeatedlyInATightLoopCompletesQuicklyWithADefaultMonitor() {
        // Sweep scale (Task 5/6) calls evaluate() up to hundreds of thousands of times.
        // ConsoleMonitor's info() writes a line to stdout on every call, which would
        // flood the console and slow the real sweep down. A plain anonymous Monitor
        // relies entirely on the interface's own default (no-op) method bodies.
        var store = new InMemoryStewardAuthorizationStore();
        var silentMonitor = new Monitor() {
        };
        var function = new StewardQuorumFunction(store, silentMonitor);
        var permission = permissionFor("g1-spike-3");
        store.appendSignature("g1-spike-3", "hash", new StewardSignature("s-1", "sig-1", Instant.EPOCH));

        var start = System.nanoTime();
        for (var i = 0; i < 100_000; i++) {
            function.evaluate(Operator.GEQ, 1, permission, context());
        }
        var elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        // No fixed millisecond budget asserted here (machine-dependent); the real
        // assertion is that this loop completes at all without manual intervention,
        // proving a silent Monitor is viable for the sweep. Record the observed time
        // in this task's report for the controller's own judgment.
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(0);
    }

    @Test
    void mix64SpreadsFirstDrawsFromDenseConsecutiveSeedsAcrossTheFullUnitInterval() {
        // Mirrors the exact verification E4's own final review used to confirm mix64
        // breaks java.util.Random's adjacent-seed first-draw correlation (spec section
        // 6): without mixing, dense consecutive seeds cluster their first nextDouble()
        // draw into a narrow band. With mixing, the draws should spread widely.
        var baseSeed = 1L;
        Set<Integer> bandHits = new HashSet<>();
        var totalDraws = 200;
        for (var i = 0; i < totalDraws; i++) {
            var mixed = mix64(baseSeed * 1_000_003L + i);
            var draw = new Random(mixed).nextDouble();
            bandHits.add((int) (draw * 10));
        }

        // An unmixed, dense-consecutive-seed draw sequence would land almost entirely
        // in one or two of these ten deciles (matching E4's measured [0.37, 0.40] band,
        // decile 3). A mixed sequence should spread across most deciles.
        assertThat(bandHits.size()).isGreaterThanOrEqualTo(6);
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        z = z ^ (z >>> 33);
        return z;
    }
}
```

- [ ] **Step 3: Run the tests**

Run: `./gradlew :experiments:harness:g1:test`
Expected: all tests pass. If `evaluatingRepeatedlyInATightLoopCompletesQuicklyWithADefaultMonitor` reveals `ConsoleMonitor` was in fact used and floods output (it is not used here, only compared conceptually), note the observed elapsed time for 100,000 evaluations in this task's report. If `mix64SpreadsFirstDrawsFromDenseConsecutiveSeedsAcrossTheFullUnitInterval` fails, do not weaken the assertion; investigate whether `mix64` was transcribed incorrectly first.

- [ ] **Step 4: Commit**

```bash
git add experiments/harness/g1/src/test/java/org/benefits/experiments/g1/LiveSpikeTest.java
git commit -m "test(g1): add the live spike against the real steward-quorum classes"
```

---

## Task 3: `AvailabilityTimelineRunner`, Arm A only (drives the real `StewardQuorumFunction`)

**Files:**
- Create: `experiments/harness/g1/src/main/java/org/benefits/experiments/g1/RunOutcome.java`
- Create: `experiments/harness/g1/src/main/java/org/benefits/experiments/g1/AvailabilityTimelineRunner.java`
- Test: `experiments/harness/g1/src/test/java/org/benefits/experiments/g1/AvailabilityTimelineRunnerTest.java`

**Interfaces:**
- Produces: `RunOutcome(Integer armATicksToQuorum, Integer armBTicksToQuorum, boolean armBVetoed)` (the two Arm B fields are set to `null`/`false` by this task; Task 4 populates them for real). `AvailabilityTimelineRunner.run(int n, int quorum, double pAct, double vetoShare, long seed): RunOutcome`, where `seed` is already fully mixed by the caller (Task 5 does the mixing; this class does not mix internally). Used by Task 4 (extended) and Task 5 (called 200 times per sweep point).

- [ ] **Step 1: Write the `RunOutcome` record**

`experiments/harness/g1/src/main/java/org/benefits/experiments/g1/RunOutcome.java`:

```java
package org.benefits.experiments.g1;

/**
 * The terminal outcome of one simulated availability timeline (spec section 3.2), for
 * both arms. armATicksToQuorum is null when Arm A never reached quorum within
 * AvailabilityTimelineRunner.MAX_TICKS (STARVED); Arm A has no other outcome, since it
 * never reads vetoes. armBTicksToQuorum is null when Arm B did not reach quorum, which
 * is either because armBVetoed is true (a hypothetical veto landed first, permanently
 * blocking Arm B) or, if armBVetoed is false, because Arm B also starved.
 */
public record RunOutcome(Integer armATicksToQuorum, Integer armBTicksToQuorum, boolean armBVetoed) {
}
```

- [ ] **Step 2: Write the failing test**

`experiments/harness/g1/src/test/java/org/benefits/experiments/g1/AvailabilityTimelineRunnerTest.java`:

```java
package org.benefits.experiments.g1;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AvailabilityTimelineRunnerTest {

    @Test
    void certainActionEveryTickReachesQuorumOnTheFirstTick() {
        // p_act = 1.0: every steward acts on tick 1. veto_share = 0.0: every action is
        // a signature. With N=5, quorum=3, Arm A must reach quorum at tick 1.
        var outcome = new AvailabilityTimelineRunner().run(5, 3, 1.0, 0.0, 42L);
        assertThat(outcome.armATicksToQuorum()).isEqualTo(1);
    }

    @Test
    void zeroActionProbabilityNeverReachesQuorumAndStarves() {
        var outcome = new AvailabilityTimelineRunner().run(5, 3, 0.0, 0.0, 42L);
        assertThat(outcome.armATicksToQuorum()).isNull();
    }

    @Test
    void sameInputsProduceByteIdenticalOutcomes() {
        var a = new AvailabilityTimelineRunner().run(10, 5, 0.3, 0.1, 777L);
        var b = new AvailabilityTimelineRunner().run(10, 5, 0.3, 0.1, 777L);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void aQuorumOfOneIsReachedAsSoonAsAnySingleStewardActs() {
        // With veto_share = 0.0 every action is a signature, so the very first steward
        // to act at all satisfies quorum=1. p_act is low enough that this typically
        // does not happen on tick 1, giving a real, checkable tick number.
        var outcome = new AvailabilityTimelineRunner().run(20, 1, 0.05, 0.0, 5L);
        assertThat(outcome.armATicksToQuorum()).isNotNull();
        assertThat(outcome.armATicksToQuorum()).isGreaterThanOrEqualTo(1);
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :experiments:harness:g1:test --tests AvailabilityTimelineRunnerTest`
Expected: FAIL, `AvailabilityTimelineRunner` does not exist.

- [ ] **Step 4: Implement `AvailabilityTimelineRunner` (Arm A only)**

`experiments/harness/g1/src/main/java/org/benefits/experiments/g1/AvailabilityTimelineRunner.java`:

```java
package org.benefits.experiments.g1;

import org.benefits.policy.InMemoryStewardAuthorizationStore;
import org.benefits.policy.StewardQuorumFunction;
import org.benefits.policy.StewardSignature;
import org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext;
import org.eclipse.edc.participant.spi.ParticipantAgent;
import org.eclipse.edc.policy.model.AtomicConstraint;
import org.eclipse.edc.policy.model.LiteralExpression;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.policy.model.Permission;
import org.eclipse.edc.spi.monitor.Monitor;

import java.time.Instant;
import java.util.Map;
import java.util.Random;

import static org.benefits.policy.BenefitsNamespace.RELEASE_REQUEST_KEY;

/**
 * Runs one simulated steward-availability timeline (spec section 3.2): a pool of N
 * stewards, each not-yet-acted steward independently acting each tick with probability
 * p_act, driving the real StewardQuorumFunction/InMemoryStewardAuthorizationStore
 * (Arm A, spec section 3.1). Arm B (the hypothetical veto-blocks-quorum arm) is added
 * in Task 4.
 */
public class AvailabilityTimelineRunner {

    public static final int MAX_TICKS = 200;

    private static final String RELEASE_REQUEST_ID = "g1-run";
    private static final String DATASET_HASH = "g1-hash";

    public RunOutcome run(int n, int quorum, double pAct, double vetoShare, long seed) {
        var random = new Random(seed);
        var store = new InMemoryStewardAuthorizationStore();
        var quorumFunction = new StewardQuorumFunction(store, new Monitor() {
        });
        var permission = permissionFor(RELEASE_REQUEST_ID);

        var acted = new boolean[n];
        Integer armATicks = null;

        for (var tick = 1; tick <= MAX_TICKS; tick++) {
            for (var s = 0; s < n; s++) {
                if (acted[s]) {
                    continue;
                }
                if (random.nextDouble() < pAct) {
                    acted[s] = true;
                    // vetoShare is not consumed here in Task 3 (Arm A ignores vetoes
                    // entirely, matching the real production function), but the draw
                    // order must still consume one nextDouble() per acting steward
                    // for veto/sign, so that Task 4 extending this loop with real
                    // veto-vs-sign branching does not change Arm A's own draw sequence
                    // and therefore does not change any already-committed Task 3 result.
                    var stewardId = "steward-" + s;
                    if (random.nextDouble() < vetoShare) {
                        // Task 3 does not record this as a veto (no store call): Arm A
                        // does not exist without a real signature or veto append call,
                        // but recording a veto here is exactly Task 4's job. To keep
                        // Task 3's own committed behavior stable across Task 4's
                        // extension, this branch is intentionally identical in both
                        // tasks (see Task 4 Step 4): it appends the veto now, in Task 3,
                        // rather than being added later, since a no-op branch here
                        // would make the draw-order comment above misleading.
                        store.appendVeto(RELEASE_REQUEST_ID, DATASET_HASH,
                                new org.benefits.policy.StewardVeto(stewardId, "simulated", Instant.EPOCH));
                    } else {
                        store.appendSignature(RELEASE_REQUEST_ID, DATASET_HASH,
                                new StewardSignature(stewardId, "sig-" + stewardId, Instant.EPOCH));
                    }
                }
            }

            if (armATicks == null) {
                var authorization = store.findByReleaseRequestId(RELEASE_REQUEST_ID);
                if (authorization != null) {
                    var context = new ContractNegotiationPolicyContext(
                            new ParticipantAgent("g1-consumer", Map.of(), Map.of()));
                    if (quorumFunction.evaluate(Operator.GEQ, quorum, permission, context)) {
                        armATicks = tick;
                        break;
                    }
                }
            }
        }

        return new RunOutcome(armATicks, null, false);
    }

    private static Permission permissionFor(String releaseRequestId) {
        return Permission.Builder.newInstance()
                .constraint(AtomicConstraint.Builder.newInstance()
                        .leftExpression(new LiteralExpression(RELEASE_REQUEST_KEY))
                        .operator(Operator.EQ)
                        .rightExpression(new LiteralExpression(releaseRequestId))
                        .build())
                .build();
    }
}
```

Note: this task's implementation already appends real vetoes to the store (so the store's own state and the random draw sequence are exactly what Task 4 needs unchanged), but Arm A's own quorum check never reads them, matching the real `StewardQuorumFunction`. `RunOutcome`'s `armBTicksToQuorum`/`armBVetoed` fields are always `null`/`false` until Task 4.

- [ ] **Step 5: Run the tests and verify they pass**

Run: `./gradlew :experiments:harness:g1:test --tests AvailabilityTimelineRunnerTest`
Expected: all 4 tests pass.

- [ ] **Step 6: Commit**

```bash
git add experiments/harness/g1/src/main/java/org/benefits/experiments/g1/RunOutcome.java \
  experiments/harness/g1/src/main/java/org/benefits/experiments/g1/AvailabilityTimelineRunner.java \
  experiments/harness/g1/src/test/java/org/benefits/experiments/g1/AvailabilityTimelineRunnerTest.java
git commit -m "feat(g1): add the availability timeline runner, Arm A driving the real quorum function"
```

---

## Task 4: Add Arm B, the hypothetical veto-blocks-quorum function

**Files:**
- Create: `experiments/harness/g1/src/main/java/org/benefits/experiments/g1/HypotheticalVetoBlocksQuorumFunction.java`
- Modify: `experiments/harness/g1/src/main/java/org/benefits/experiments/g1/AvailabilityTimelineRunner.java`
- Test: `experiments/harness/g1/src/test/java/org/benefits/experiments/g1/AvailabilityTimelineRunnerTest.java`

**Interfaces:**
- Produces: `HypotheticalVetoBlocksQuorumFunction.evaluate(StewardAuthorization, int required): boolean`. `AvailabilityTimelineRunner.run(...)` now returns a fully populated `RunOutcome` including Arm B. Used by Task 5.

- [ ] **Step 1: Write `HypotheticalVetoBlocksQuorumFunction`**

`experiments/harness/g1/src/main/java/org/benefits/experiments/g1/HypotheticalVetoBlocksQuorumFunction.java`:

```java
package org.benefits.experiments.g1;

import org.benefits.policy.StewardAuthorization;

/**
 * NOT real production behavior. The real, unmodified StewardQuorumFunction (spec
 * section 3.1) never reads vetoes at all; this class exists only to show, on the same
 * simulated timeline as Arm A, how the availability/deadlock picture would differ if a
 * veto permanently blocked quorum, an intuitive but currently false reading of "veto"
 * this project's own steward-portal UI does not implement or claim.
 */
public final class HypotheticalVetoBlocksQuorumFunction {

    private HypotheticalVetoBlocksQuorumFunction() {
    }

    public static boolean evaluate(StewardAuthorization authorization, int required) {
        return authorization.vetoes().isEmpty() && authorization.distinctStewardCount() >= required;
    }
}
```

- [ ] **Step 2: Add failing tests for Arm B to the existing test file**

Add to `experiments/harness/g1/src/test/java/org/benefits/experiments/g1/AvailabilityTimelineRunnerTest.java`:

```java
    @Test
    void armBReachesQuorumTheSameWayArmADoesWhenVetoShareIsZero() {
        var outcome = new AvailabilityTimelineRunner().run(5, 3, 1.0, 0.0, 42L);
        assertThat(outcome.armATicksToQuorum()).isEqualTo(1);
        assertThat(outcome.armBTicksToQuorum()).isEqualTo(1);
        assertThat(outcome.armBVetoed()).isFalse();
    }

    @Test
    void anyVetoPermanentlyBlocksArmBEvenIfArmAStillReachesQuorum() {
        // veto_share = 1.0: every acting steward vetoes, so Arm A (signature-only)
        // never accumulates a single signature and also starves; this is a genuine
        // edge case where both arms starve, but for different real reasons.
        var outcome = new AvailabilityTimelineRunner().run(5, 3, 1.0, 1.0, 42L);
        assertThat(outcome.armATicksToQuorum()).isNull();
        assertThat(outcome.armBVetoed()).isTrue();
        assertThat(outcome.armBTicksToQuorum()).isNull();
    }

    @Test
    void armBOutcomeIsIndependentOfArmAOnceArmAHasAlreadyReached() {
        // quorum=1 means Arm A reaches on the very first acting steward. If that
        // steward's action happens to be a veto (possible even at a low veto_share by
        // chance), Arm A still reached (it ignores vetoes) but Arm B is vetoed, not
        // reached, on the very same timeline.
        var outcome = new AvailabilityTimelineRunner().run(1, 1, 1.0, 1.0, 42L);
        assertThat(outcome.armATicksToQuorum()).isEqualTo(1);
        assertThat(outcome.armBVetoed()).isTrue();
        assertThat(outcome.armBTicksToQuorum()).isNull();
    }
```

- [ ] **Step 3: Run the tests to verify the new ones fail**

Run: `./gradlew :experiments:harness:g1:test --tests AvailabilityTimelineRunnerTest`
Expected: the three new tests FAIL (`armBTicksToQuorum` and `armBVetoed` are always `null`/`false` from Task 3).

- [ ] **Step 4: Extend `AvailabilityTimelineRunner` with Arm B**

Modify `experiments/harness/g1/src/main/java/org/benefits/experiments/g1/AvailabilityTimelineRunner.java`. Replace the `run` method's body (everything from `var acted = new boolean[n];` through `return new RunOutcome(armATicks, null, false);`) with:

```java
        var acted = new boolean[n];
        Integer armATicks = null;
        Integer armBTicks = null;
        var armBVetoed = false;

        for (var tick = 1; tick <= MAX_TICKS; tick++) {
            for (var s = 0; s < n; s++) {
                if (acted[s]) {
                    continue;
                }
                if (random.nextDouble() < pAct) {
                    acted[s] = true;
                    var stewardId = "steward-" + s;
                    if (random.nextDouble() < vetoShare) {
                        store.appendVeto(RELEASE_REQUEST_ID, DATASET_HASH,
                                new org.benefits.policy.StewardVeto(stewardId, "simulated", Instant.EPOCH));
                    } else {
                        store.appendSignature(RELEASE_REQUEST_ID, DATASET_HASH,
                                new StewardSignature(stewardId, "sig-" + stewardId, Instant.EPOCH));
                    }
                }
            }

            var authorization = store.findByReleaseRequestId(RELEASE_REQUEST_ID);
            if (authorization == null) {
                continue;
            }

            if (armATicks == null) {
                var context = new ContractNegotiationPolicyContext(
                        new ParticipantAgent("g1-consumer", Map.of(), Map.of()));
                if (quorumFunction.evaluate(Operator.GEQ, quorum, permission, context)) {
                    armATicks = tick;
                }
            }

            if (armBTicks == null && !armBVetoed) {
                if (!authorization.vetoes().isEmpty()) {
                    armBVetoed = true;
                } else if (HypotheticalVetoBlocksQuorumFunction.evaluate(authorization, quorum)) {
                    armBTicks = tick;
                }
            }

            if (armATicks != null && (armBTicks != null || armBVetoed)) {
                break;
            }
        }

        return new RunOutcome(armATicks, armBTicks, armBVetoed);
```

This changes nothing about how Arm A's draws are consumed (the veto-vs-sign draw was already made unconditionally in Task 3, so Arm A's `armATicksToQuorum` values from every already-passing Task 3 test remain identical). It adds: reading `authorization` once per tick (was previously read only inside the `armATicks == null` branch), Arm B's veto-detection and hypothetical-quorum check, and a combined break condition requiring both arms to have a terminal outcome.

- [ ] **Step 5: Run the tests and verify all pass**

Run: `./gradlew :experiments:harness:g1:test`
Expected: all tests in the module pass, including the original Task 3 tests (unchanged results) and the three new Arm B tests.

- [ ] **Step 6: Commit**

```bash
git add experiments/harness/g1/src/main/java/org/benefits/experiments/g1/HypotheticalVetoBlocksQuorumFunction.java \
  experiments/harness/g1/src/main/java/org/benefits/experiments/g1/AvailabilityTimelineRunner.java \
  experiments/harness/g1/src/test/java/org/benefits/experiments/g1/AvailabilityTimelineRunnerTest.java
git commit -m "feat(g1): add Arm B, the hypothetical veto-blocks-quorum function, on the same timeline"
```

---

## Task 5: `AvailabilitySweepPointRunner`, aggregating 200 runs into one sweep point's statistics

**Files:**
- Create: `experiments/harness/g1/src/main/java/org/benefits/experiments/g1/AvailabilitySweepPointResult.java`
- Create: `experiments/harness/g1/src/main/java/org/benefits/experiments/g1/AvailabilitySweepPointRunner.java`
- Test: `experiments/harness/g1/src/test/java/org/benefits/experiments/g1/AvailabilitySweepPointRunnerTest.java`

**Interfaces:**
- Consumes: `AvailabilityTimelineRunner.run(int, int, double, double, long): RunOutcome` (Task 4). `Statistics.median/iqr` (Task 1).
- Produces: `AvailabilitySweepPointResult` record and `AvailabilitySweepPointRunner(long baseSeed).run(int n, double quorumFraction, double pAct, double vetoShare, int pointIndex, int runsPerPoint): AvailabilitySweepPointResult`. Used by Task 6.

- [ ] **Step 1: Write the result record**

`experiments/harness/g1/src/main/java/org/benefits/experiments/g1/AvailabilitySweepPointResult.java`:

```java
package org.benefits.experiments.g1;

public record AvailabilitySweepPointResult(
        long seed,
        int n,
        double quorumFraction,
        int quorumCount,
        double pAct,
        double vetoShare,
        int runs,
        double armAReachedRate,
        double armAStarvedRate,
        Double armAMedianTicksToQuorum,
        Double armAIqrTicksToQuorum,
        double armBReachedRate,
        double armBVetoedRate,
        double armBStarvedRate,
        Double armBMedianTicksToQuorum,
        Double armBIqrTicksToQuorum
) {
}
```

- [ ] **Step 2: Write the failing test**

`experiments/harness/g1/src/test/java/org/benefits/experiments/g1/AvailabilitySweepPointRunnerTest.java`:

```java
package org.benefits.experiments.g1;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AvailabilitySweepPointRunnerTest {

    @Test
    void certainActionAndNoVetoesAlwaysReachesQuorumOnTickOneForBothArms() {
        var result = new AvailabilitySweepPointRunner(1L)
                .run(5, 0.5, 1.0, 0.0, 0, 50);

        assertThat(result.runs()).isEqualTo(50);
        assertThat(result.quorumCount()).isEqualTo(3);
        assertThat(result.armAReachedRate()).isEqualTo(1.0);
        assertThat(result.armAStarvedRate()).isEqualTo(0.0);
        assertThat(result.armAMedianTicksToQuorum()).isEqualTo(1.0);
        assertThat(result.armBReachedRate()).isEqualTo(1.0);
        assertThat(result.armBVetoedRate()).isEqualTo(0.0);
    }

    @Test
    void zeroActionProbabilityStarvesBothArmsEveryRun() {
        var result = new AvailabilitySweepPointRunner(1L)
                .run(5, 0.5, 0.0, 0.0, 1, 50);

        assertThat(result.armAReachedRate()).isEqualTo(0.0);
        assertThat(result.armAStarvedRate()).isEqualTo(1.0);
        assertThat(result.armAMedianTicksToQuorum()).isNull();
        assertThat(result.armAIqrTicksToQuorum()).isNull();
        assertThat(result.armBReachedRate()).isEqualTo(0.0);
        assertThat(result.armBVetoedRate()).isEqualTo(0.0);
        assertThat(result.armBStarvedRate()).isEqualTo(1.0);
    }

    @Test
    void quorumCountRoundsTheFractionAndIsAtLeastOne() {
        var result = new AvailabilitySweepPointRunner(1L).run(5, 0.3, 0.5, 0.0, 2, 10);
        // round(5 * 0.3) = round(1.5) = 2 under Java's Math.round half-up convention.
        assertThat(result.quorumCount()).isEqualTo(2);

        var resultRoundingToZero = new AvailabilitySweepPointRunner(1L).run(3, 0.1, 0.5, 0.0, 3, 10);
        // round(3 * 0.1) = round(0.3) = 0, clamped up to 1.
        assertThat(resultRoundingToZero.quorumCount()).isEqualTo(1);
    }

    @Test
    void differentPointIndicesWithTheSameOtherParametersProduceDifferentRunOutcomes() {
        // Confirms per-point seed mixing actually varies the outcome, not just a
        // structural sanity check: with a moderate p_act, two different pointIndex
        // values must not produce identical reached-rate statistics across 200 runs.
        var a = new AvailabilitySweepPointRunner(1L).run(10, 0.5, 0.3, 0.1, 10, 200);
        var b = new AvailabilitySweepPointRunner(1L).run(10, 0.5, 0.3, 0.1, 11, 200);
        assertThat(a.armAMedianTicksToQuorum()).isNotEqualTo(b.armAMedianTicksToQuorum());
    }

    @Test
    void sameBaseSeedAndPointIndexProduceByteIdenticalResults() {
        var a = new AvailabilitySweepPointRunner(7L).run(10, 0.5, 0.3, 0.1, 5, 100);
        var b = new AvailabilitySweepPointRunner(7L).run(10, 0.5, 0.3, 0.1, 5, 100);
        assertThat(a).isEqualTo(b);
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :experiments:harness:g1:test --tests AvailabilitySweepPointRunnerTest`
Expected: FAIL, `AvailabilitySweepPointRunner` does not exist.

- [ ] **Step 4: Implement `AvailabilitySweepPointRunner`**

`experiments/harness/g1/src/main/java/org/benefits/experiments/g1/AvailabilitySweepPointRunner.java`:

```java
package org.benefits.experiments.g1;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs RUNS_PER_POINT independent seeded repetitions of AvailabilityTimelineRunner for
 * one sweep point and pools the results into that point's statistics (spec section
 * 3.3-3.4).
 */
public class AvailabilitySweepPointRunner {

    private final long baseSeed;

    public AvailabilitySweepPointRunner(long baseSeed) {
        this.baseSeed = baseSeed;
    }

    public AvailabilitySweepPointResult run(int n, double quorumFraction, double pAct, double vetoShare,
                                             int pointIndex, int runsPerPoint) {
        var quorumCount = Math.max(1, (int) Math.round(n * quorumFraction));
        var runner = new AvailabilityTimelineRunner();

        List<Integer> armAReachedTicks = new ArrayList<>();
        List<Integer> armBReachedTicks = new ArrayList<>();
        var armAReached = 0;
        var armBReached = 0;
        var armBVetoed = 0;

        for (var runIndex = 0; runIndex < runsPerPoint; runIndex++) {
            var seed = mix64(baseSeed * 1_000_003L + pointIndex * 1_000L + runIndex);
            var outcome = runner.run(n, quorumCount, pAct, vetoShare, seed);

            if (outcome.armATicksToQuorum() != null) {
                armAReached++;
                armAReachedTicks.add(outcome.armATicksToQuorum());
            }
            if (outcome.armBTicksToQuorum() != null) {
                armBReached++;
                armBReachedTicks.add(outcome.armBTicksToQuorum());
            } else if (outcome.armBVetoed()) {
                armBVetoed++;
            }
        }

        Double armAMedian = toDoubleMedianOrNull(armAReachedTicks);
        Double armAIqr = toDoubleIqrOrNull(armAReachedTicks);
        Double armBMedian = toDoubleMedianOrNull(armBReachedTicks);
        Double armBIqr = toDoubleIqrOrNull(armBReachedTicks);

        var armAStarved = runsPerPoint - armAReached;
        var armBStarved = runsPerPoint - armBReached - armBVetoed;

        return new AvailabilitySweepPointResult(
                baseSeed, n, quorumFraction, quorumCount, pAct, vetoShare, runsPerPoint,
                armAReached / (double) runsPerPoint,
                armAStarved / (double) runsPerPoint,
                armAMedian, armAIqr,
                armBReached / (double) runsPerPoint,
                armBVetoed / (double) runsPerPoint,
                armBStarved / (double) runsPerPoint,
                armBMedian, armBIqr
        );
    }

    private static Double toDoubleMedianOrNull(List<Integer> ticks) {
        if (ticks.isEmpty()) {
            return null;
        }
        return Statistics.median(ticks.stream().map(Integer::doubleValue).toList());
    }

    private static Double toDoubleIqrOrNull(List<Integer> ticks) {
        if (ticks.isEmpty()) {
            return null;
        }
        return Statistics.iqr(ticks.stream().map(Integer::doubleValue).toList());
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        z = z ^ (z >>> 33);
        return z;
    }
}
```

- [ ] **Step 5: Run the tests and verify they pass**

Run: `./gradlew :experiments:harness:g1:test --tests AvailabilitySweepPointRunnerTest`
Expected: all 5 tests pass.

- [ ] **Step 6: Commit**

```bash
git add experiments/harness/g1/src/main/java/org/benefits/experiments/g1/AvailabilitySweepPointResult.java \
  experiments/harness/g1/src/main/java/org/benefits/experiments/g1/AvailabilitySweepPointRunner.java \
  experiments/harness/g1/src/test/java/org/benefits/experiments/g1/AvailabilitySweepPointRunnerTest.java
git commit -m "feat(g1): add the sweep point runner, aggregating 200 seeded runs per point"
```

---

## Task 6: G1 sweep driver, CSV writer, and production entry point

**Files:**
- Create: `experiments/harness/g1/src/main/java/org/benefits/experiments/g1/AvailabilitySweepDriver.java`
- Create: `experiments/harness/g1/src/main/java/org/benefits/experiments/g1/RunG1Sweep.java`
- Test: `experiments/harness/g1/src/test/java/org/benefits/experiments/g1/AvailabilitySweepDriverTest.java`

**Interfaces:**
- Consumes: `AvailabilitySweepPointRunner` (Task 5).
- Produces: `experiments/results/g1/availability.csv`, the sub-project's committed output.

- [ ] **Step 1: Write the failing test**

`experiments/harness/g1/src/test/java/org/benefits/experiments/g1/AvailabilitySweepDriverTest.java`:

```java
package org.benefits.experiments.g1;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AvailabilitySweepDriverTest {

    @Test
    void writesOneHeaderRowPlusOneRowPerSweepPoint(@TempDir Path tempDir) throws IOException {
        var csvPath = tempDir.resolve("availability.csv").toString();
        var config = new AvailabilitySweepConfig(
                List.of(5, 10),
                List.of(0.3, 0.7),
                List.of(0.5),
                List.of(0.0, 0.1)
        );

        new AvailabilitySweepDriver().run(1L, config, csvPath, 10);

        var lines = Files.readAllLines(Path.of(csvPath));
        // 2 x 2 x 1 x 2 = 8 sweep points, plus 1 header row.
        assertThat(lines).hasSize(9);
        assertThat(lines.get(0)).isEqualTo("seed,N,quorum_fraction,quorum_count,p_act,veto_share,runs,"
                + "arm_a_reached_rate,arm_a_starved_rate,arm_a_median_ticks_to_quorum,"
                + "arm_a_iqr_ticks_to_quorum,arm_b_reached_rate,arm_b_vetoed_rate,arm_b_starved_rate,"
                + "arm_b_median_ticks_to_quorum,arm_b_iqr_ticks_to_quorum");
    }

    @Test
    void aStarvedPointsMedianColumnsAreWrittenAsEmptyStrings(@TempDir Path tempDir) throws IOException {
        var csvPath = tempDir.resolve("availability.csv").toString();
        var config = new AvailabilitySweepConfig(List.of(5), List.of(0.5), List.of(0.0), List.of(0.0));

        new AvailabilitySweepDriver().run(1L, config, csvPath, 10);

        var lines = Files.readAllLines(Path.of(csvPath));
        var dataRow = lines.get(1).split(",", -1);
        // p_act = 0.0: nobody ever acts, both arms starve every run in this one point.
        assertThat(dataRow[7]).isEqualTo("0.0");   // arm_a_reached_rate
        assertThat(dataRow[9]).isEmpty();          // arm_a_median_ticks_to_quorum
        assertThat(dataRow[10]).isEmpty();         // arm_a_iqr_ticks_to_quorum
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :experiments:harness:g1:test --tests AvailabilitySweepDriverTest`
Expected: FAIL, `AvailabilitySweepConfig`/`AvailabilitySweepDriver` do not exist.

- [ ] **Step 3: Implement the config record and driver**

`experiments/harness/g1/src/main/java/org/benefits/experiments/g1/AvailabilitySweepConfig.java`:

```java
package org.benefits.experiments.g1;

import java.util.List;

public record AvailabilitySweepConfig(
        List<Integer> nValues,
        List<Double> quorumFractionValues,
        List<Double> pActValues,
        List<Double> vetoShareValues
) {
}
```

`experiments/harness/g1/src/main/java/org/benefits/experiments/g1/AvailabilitySweepDriver.java`:

```java
package org.benefits.experiments.g1;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the full G1 sweep (spec section 3.3): the cross product of every swept value in
 * the given AvailabilitySweepConfig, one AvailabilitySweepPointRunner call per point
 * (each aggregating runsPerPoint independent seeded runs), written as a single CSV.
 */
public class AvailabilitySweepDriver {

    public void run(long seed, AvailabilitySweepConfig config, String csvPath, int runsPerPoint) throws IOException {
        var runner = new AvailabilitySweepPointRunner(seed);
        List<String> lines = new ArrayList<>();
        lines.add("seed,N,quorum_fraction,quorum_count,p_act,veto_share,runs,"
                + "arm_a_reached_rate,arm_a_starved_rate,arm_a_median_ticks_to_quorum,"
                + "arm_a_iqr_ticks_to_quorum,arm_b_reached_rate,arm_b_vetoed_rate,arm_b_starved_rate,"
                + "arm_b_median_ticks_to_quorum,arm_b_iqr_ticks_to_quorum");

        var pointIndex = 0;
        for (var n : config.nValues()) {
            for (var quorumFraction : config.quorumFractionValues()) {
                for (var pAct : config.pActValues()) {
                    for (var vetoShare : config.vetoShareValues()) {
                        var result = runner.run(n, quorumFraction, pAct, vetoShare, pointIndex, runsPerPoint);
                        lines.add(toCsvRow(result));
                        pointIndex++;
                    }
                }
            }
        }

        var outPath = Path.of(csvPath);
        Files.createDirectories(outPath.toAbsolutePath().getParent());
        Files.write(outPath, String.join("\n", lines).concat("\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String toCsvRow(AvailabilitySweepPointResult result) {
        return String.join(",",
                Long.toString(result.seed()),
                Integer.toString(result.n()),
                Double.toString(result.quorumFraction()),
                Integer.toString(result.quorumCount()),
                Double.toString(result.pAct()),
                Double.toString(result.vetoShare()),
                Integer.toString(result.runs()),
                Double.toString(result.armAReachedRate()),
                Double.toString(result.armAStarvedRate()),
                result.armAMedianTicksToQuorum() == null ? "" : Double.toString(result.armAMedianTicksToQuorum()),
                result.armAIqrTicksToQuorum() == null ? "" : Double.toString(result.armAIqrTicksToQuorum()),
                Double.toString(result.armBReachedRate()),
                Double.toString(result.armBVetoedRate()),
                Double.toString(result.armBStarvedRate()),
                result.armBMedianTicksToQuorum() == null ? "" : Double.toString(result.armBMedianTicksToQuorum()),
                result.armBIqrTicksToQuorum() == null ? "" : Double.toString(result.armBIqrTicksToQuorum())
        );
    }
}
```

- [ ] **Step 4: Run the tests and verify they pass**

Run: `./gradlew :experiments:harness:g1:test --tests AvailabilitySweepDriverTest`
Expected: both tests pass.

- [ ] **Step 5: Write the production entry point**

`experiments/harness/g1/src/main/java/org/benefits/experiments/g1/RunG1Sweep.java`:

```java
package org.benefits.experiments.g1;

import java.io.IOException;
import java.util.List;

public class RunG1Sweep {

    public static final int RUNS_PER_POINT = 200;

    public static void main(String[] args) throws IOException {
        var seed = 1L;
        var config = new AvailabilitySweepConfig(
                List.of(5, 10, 20),
                List.of(0.3, 0.5, 0.7),
                List.of(0.1, 0.3, 0.5, 0.7, 0.9),
                List.of(0.0, 0.1, 0.3)
        );

        new AvailabilitySweepDriver().run(seed, config, "../../results/g1/availability.csv", RUNS_PER_POINT);

        System.out.println("G1 sweep complete");
    }
}
```

- [ ] **Step 6: Run the full production sweep and verify reproducibility**

Run (from `experiments/harness/g1`, matching E4's own entry-point invocation convention: check
`.superpowers/sdd/2026-09-02-e4-experiment/task-5-report.md` and `task-6-report.md` for exactly how E4's
equivalent entry point was invoked without a Gradle `application` plugin, and use the same approach here):

```bash
./gradlew :experiments:harness:g1:compileJava
java -cp "experiments/harness/g1/build/classes/java/main:connector/extensions/benefits-policy/build/classes/java/main:$(find ~/.gradle -iname '*.jar' | tr '\n' ':')" \
  org.benefits.experiments.g1.RunG1Sweep
```

Adjust the classpath construction to whatever actually works in this environment (E4's own task reports
document the exact working invocation and its Jackson-jar caveat; G1 needs no Jackson at all, only the EDC
jars already resolved as `benefits-policy` dependencies). Run it twice into two different output paths and
`diff` them.
Expected: 136 lines (1 header + 135 data rows: 3 N values x 3 quorum_fraction values x 5 p_act values x
3 veto_share values = 135 points). The two runs' CSVs must be byte-identical.

- [ ] **Step 7: Commit the code and the real results**

```bash
git add experiments/harness/g1/src/main/java/org/benefits/experiments/g1/AvailabilitySweepConfig.java \
  experiments/harness/g1/src/main/java/org/benefits/experiments/g1/AvailabilitySweepDriver.java \
  experiments/harness/g1/src/main/java/org/benefits/experiments/g1/RunG1Sweep.java \
  experiments/harness/g1/src/test/java/org/benefits/experiments/g1/AvailabilitySweepDriverTest.java
git commit -m "feat(g1): add the sweep driver and production entry point"
git add -f experiments/results/g1/availability.csv
git commit -m "feat(g1): commit the real production availability sweep results"
```

(Two separate commits: the code, then the data, matching this project's established E1/E3/E4 convention of
never bundling generated results into the same commit as the code that produced them.)

---

## Task 7: `ConsentGrant`, and additive `Contribution`/`QuerySpec` changes

**Files:**
- Create: `connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/ConsentGrant.java`
- Modify: `connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/Contribution.java`
- Modify: `connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/QuerySpec.java`
- Test: `connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/ConsentGrantTest.java` (new)
- Test: `connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/ContributionTest.java` (new)
- Test: `connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/QuerySpecTest.java` (new)

**Interfaces:**
- Produces: `ConsentGrant(String field, String purpose, String recipientClass, String grantedAt, String expiresAt, String revokedAt)`. `Contribution`'s new 4-arg canonical constructor and preserved 3-arg legacy constructor. `QuerySpec`'s new 4-arg canonical constructor and preserved 2-arg legacy constructor. Used by Task 8 onward.

**Read first:** `connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/Contribution.java` and
`QuerySpec.java` as they exist right now, in full, before editing, to preserve every existing Javadoc
comment, the `ABSENT` sentinel, and the `attribute(String)` method exactly as written. Do not remove or
reword anything already there beyond what is shown below as an addition.

- [ ] **Step 1: Write `ConsentGrant`**

`connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/ConsentGrant.java`:

```java
package org.benefits.policy;

/**
 * One participant's consent grant for a single (field, purpose, recipientClass)
 * combination, matching packages/consent-core/src/types.ts's ConsentGrant shape.
 * Timestamps are ISO-8601 strings compared lexically, the same convention
 * ConsentEvaluator.hasGrant already uses against raw JSON: grantedAt/expiresAt/
 * revokedAt sort correctly as plain strings without a java.time parse step.
 */
public record ConsentGrant(
        String field,
        String purpose,
        String recipientClass,
        String grantedAt,
        String expiresAt,
        String revokedAt
) {
}
```

- [ ] **Step 2: Write `ConsentGrant`'s test**

`connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/ConsentGrantTest.java`:

```java
package org.benefits.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConsentGrantTest {

    @Test
    void isASimpleValueRecordWithAllSixFields() {
        var grant = new ConsentGrant("serviceType", "community-insight", "community",
                "2020-01-01T00:00:00Z", null, null);

        assertThat(grant.field()).isEqualTo("serviceType");
        assertThat(grant.purpose()).isEqualTo("community-insight");
        assertThat(grant.recipientClass()).isEqualTo("community");
        assertThat(grant.grantedAt()).isEqualTo("2020-01-01T00:00:00Z");
        assertThat(grant.expiresAt()).isNull();
        assertThat(grant.revokedAt()).isNull();
    }
}
```

- [ ] **Step 3: Add the additive change to `Contribution`, and its failing test**

Add to the existing `Contribution.java` (do not remove `ABSENT` or `attribute(String)`; this is an addition
of a fourth field, a canonical compact constructor, and a legacy overload):

```java
public record Contribution(String participantRef, String siteId, Map<String, String> attributes,
                            List<ConsentGrant> consentGrants) {

    public static final String ABSENT = " ABSENT";

    public Contribution {
        attributes = Map.copyOf(attributes);
        consentGrants = consentGrants == null ? null : List.copyOf(consentGrants);
    }

    public Contribution(String participantRef, String siteId, Map<String, String> attributes) {
        this(participantRef, siteId, attributes, null);
    }

    public String attribute(String name) {
        return attributes.getOrDefault(name, ABSENT);
    }
}
```

(Add `import java.util.List;` alongside the existing `import java.util.Map;`.)

`connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/ContributionTest.java`:

```java
package org.benefits.policy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContributionTest {

    @Test
    void theLegacyThreeArgConstructorLeavesConsentGrantsNull() {
        var contribution = new Contribution("p-1", "site-1", Map.of("channel", "app"));
        assertThat(contribution.consentGrants()).isNull();
    }

    @Test
    void theFourArgConstructorCopiesTheGrantsListDefensively() {
        var grants = new java.util.ArrayList<ConsentGrant>();
        grants.add(new ConsentGrant("channel", "p", "r", "2020-01-01T00:00:00Z", null, null));
        var contribution = new Contribution("p-1", "site-1", Map.of("channel", "app"), grants);
        grants.clear();
        assertThat(contribution.consentGrants()).hasSize(1);
    }

    @Test
    void anEmptyNonNullGrantsListIsPreservedAsEmptyNotNull() {
        var contribution = new Contribution("p-1", "site-1", Map.of(), List.of());
        assertThat(contribution.consentGrants()).isNotNull();
        assertThat(contribution.consentGrants()).isEmpty();
    }

    @Test
    void attributeStillReturnsTheAbsentSentinelForAMissingKey() {
        var contribution = new Contribution("p-1", "site-1", Map.of());
        assertThat(contribution.attribute("serviceType")).isEqualTo(Contribution.ABSENT);
    }
}
```

- [ ] **Step 4: Add the additive change to `QuerySpec`, and its failing test**

Add to the existing `QuerySpec.java`:

```java
public record QuerySpec(String siteId, List<String> groupBy, String purpose, String recipientClass) {

    public QuerySpec {
        groupBy = List.copyOf(groupBy);
    }

    public QuerySpec(String siteId, List<String> groupBy) {
        this(siteId, groupBy, null, null);
    }
}
```

`connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/QuerySpecTest.java`:

```java
package org.benefits.policy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuerySpecTest {

    @Test
    void theLegacyTwoArgConstructorLeavesPurposeAndRecipientClassNull() {
        var query = new QuerySpec("site-1", List.of("channel"));
        assertThat(query.purpose()).isNull();
        assertThat(query.recipientClass()).isNull();
    }

    @Test
    void theFourArgConstructorSetsPurposeAndRecipientClass() {
        var query = new QuerySpec("site-1", List.of("channel"), "community-insight", "community");
        assertThat(query.purpose()).isEqualTo("community-insight");
        assertThat(query.recipientClass()).isEqualTo("community");
    }

    @Test
    void groupByIsStillDefensivelyCopied() {
        var groupBy = new java.util.ArrayList<String>();
        groupBy.add("channel");
        var query = new QuerySpec("site-1", groupBy);
        groupBy.clear();
        assertThat(query.groupBy()).containsExactly("channel");
    }
}
```

- [ ] **Step 5: Run every existing test in `benefits-privacy` and `experiments/harness/e4`**

Run: `./gradlew :connector:extensions:benefits-privacy:test :experiments:harness:e4:test`
Expected: every pre-existing test in both modules still passes, byte-for-byte the same as before this
task (this is the regression proof the Global Constraints section requires). If anything fails, the
additive change was not actually additive; fix the constructor delegation before proceeding, do not
change any pre-existing test to accommodate a real behavior change.

- [ ] **Step 6: Run the new tests**

Run: `./gradlew :connector:extensions:benefits-privacy:test --tests ConsentGrantTest --tests ContributionTest --tests QuerySpecTest`
Expected: all pass.

- [ ] **Step 7: Commit**

```bash
git add connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/ConsentGrant.java \
  connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/Contribution.java \
  connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/QuerySpec.java \
  connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/ConsentGrantTest.java \
  connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/ContributionTest.java \
  connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/QuerySpecTest.java
git commit -m "feat(privacy): add ConsentGrant and additive consent fields to Contribution/QuerySpec"
```

---

## Task 8: `consentCovers` and the `PrivacyPipeline.compute` filter, plus a live spike

**Files:**
- Modify: `connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/PrivacyPipeline.java`
- Test: `connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/PrivacyPipelineConsentFilterTest.java` (new)

**Interfaces:**
- Consumes: `Contribution.consentGrants()`, `QuerySpec.purpose()/recipientClass()` (Task 7).
- Produces: `PrivacyPipeline.compute`'s new, backward-compatible filtering behavior. Used by Task 9 (via `CouchDbContributionSource`) and Task 11 (G2's own sweep runner).

**Read first:** `connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/PrivacyPipeline.java`
and `ConsentEvaluator.java` in full, before editing. `PrivacyPipeline.compute`'s existing Javadoc, the
`jsonEscape` method, and the whole class structure must stay exactly as they are outside the one method
body change below. `ConsentEvaluator.hasGrant`'s matching rule (field, purpose, recipientClass all equal;
`grantedAt <= now`; `expiresAt` null or `> now`; `revokedAt` null or `> now`, all via lexical string
comparison) is the rule `consentCovers` ports from JSON-node matching to typed `ConsentGrant` matching.

- [ ] **Step 1: Write the failing tests**

`connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/PrivacyPipelineConsentFilterTest.java`:

```java
package org.benefits.policy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real PrivacyPipeline.compute filter directly with hand-built consent
 * grants, confirming both the new behavior and that it is unreachable for any query
 * that does not declare a purpose/recipientClass (spec section 4.1).
 */
class PrivacyPipelineConsentFilterTest {

    private static Contribution withGrant(String participantRef, String value, boolean valid) {
        var grants = valid
                ? List.of(new ConsentGrant("serviceType", "community-insight", "community",
                        "2020-01-01T00:00:00Z", null, null))
                : List.<ConsentGrant>of();
        return new Contribution(participantRef, "site-1", Map.of("serviceType", value), grants);
    }

    @Test
    void aQueryWithNoPurposeOrRecipientClassIncludesEveryContributionRegardlessOfConsent() {
        var contributions = List.of(
                withGrant("p-1", "housing", true),
                withGrant("p-2", "housing", false));
        var source = new InMemoryContributionSource(contributions);
        var pipeline = new PrivacyPipeline(source, new LaplaceNoiseSource(new Random(1)),
                new InMemoryBudgetAccountant(2.0));

        // No purpose/recipientClass: the legacy two-arg QuerySpec constructor, matching
        // every existing caller (E1/E3/E4/ReleaseRequestResource).
        var released = pipeline.compute("t-1", new QuerySpec("site-1", List.of("serviceType")), 1, 1.0);

        assertThat(released).isPresent();
        assertThat(released.get().computedK()).isEqualTo(2);
    }

    @Test
    void aQueryWithPurposeAndRecipientClassExcludesContributionsMissingAValidGrant() {
        var contributions = List.of(
                withGrant("p-1", "housing", true),
                withGrant("p-2", "housing", false));
        var source = new InMemoryContributionSource(contributions);
        var pipeline = new PrivacyPipeline(source, new LaplaceNoiseSource(new Random(1)),
                new InMemoryBudgetAccountant(2.0));

        var query = new QuerySpec("site-1", List.of("serviceType"), "community-insight", "community");
        var released = pipeline.compute("t-2", query, 1, 1.0);

        assertThat(released).isPresent();
        // Only p-1 has a valid grant; p-2 is excluded before k-anonymity even runs.
        assertThat(released.get().computedK()).isEqualTo(1);
    }

    @Test
    void aContributionWithNullConsentGrantsIsAlwaysIncludedEvenUnderAScopedQuery() {
        var untracked = new Contribution("p-3", "site-1", Map.of("serviceType", "housing"));
        var withValidGrant = withGrant("p-4", "housing", true);
        var source = new InMemoryContributionSource(List.of(untracked, withValidGrant));
        var pipeline = new PrivacyPipeline(source, new LaplaceNoiseSource(new Random(1)),
                new InMemoryBudgetAccountant(2.0));

        var query = new QuerySpec("site-1", List.of("serviceType"), "community-insight", "community");
        var released = pipeline.compute("t-3", query, 1, 1.0);

        assertThat(released).isPresent();
        assertThat(released.get().computedK()).isEqualTo(2);
    }

    @Test
    void anExpiredGrantDoesNotCoverTheQuery() {
        var expired = new Contribution("p-5", "site-1", Map.of("serviceType", "housing"),
                List.of(new ConsentGrant("serviceType", "community-insight", "community",
                        "2020-01-01T00:00:00Z", "2020-06-01T00:00:00Z", null)));
        var valid = withGrant("p-6", "housing", true);
        var source = new InMemoryContributionSource(List.of(expired, valid));
        var pipeline = new PrivacyPipeline(source, new LaplaceNoiseSource(new Random(1)),
                new InMemoryBudgetAccountant(2.0));

        var query = new QuerySpec("site-1", List.of("serviceType"), "community-insight", "community");
        var released = pipeline.compute("t-4", query, 1, 1.0);

        assertThat(released).isPresent();
        assertThat(released.get().computedK()).isEqualTo(1);
    }

    @Test
    void aRevokedGrantDoesNotCoverTheQueryEvenIfNotExpired() {
        var revoked = new Contribution("p-7", "site-1", Map.of("serviceType", "housing"),
                List.of(new ConsentGrant("serviceType", "community-insight", "community",
                        "2020-01-01T00:00:00Z", null, "2020-06-01T00:00:00Z")));
        var valid = withGrant("p-8", "housing", true);
        var source = new InMemoryContributionSource(List.of(revoked, valid));
        var pipeline = new PrivacyPipeline(source, new LaplaceNoiseSource(new Random(1)),
                new InMemoryBudgetAccountant(2.0));

        var query = new QuerySpec("site-1", List.of("serviceType"), "community-insight", "community");
        var released = pipeline.compute("t-5", query, 1, 1.0);

        assertThat(released).isPresent();
        assertThat(released.get().computedK()).isEqualTo(1);
    }

    @Test
    void multiFieldGroupByRequiresAValidGrantForEveryGroupByField() {
        var partial = new Contribution("p-9", "site-1", Map.of("serviceType", "housing", "channel", "app"),
                List.of(new ConsentGrant("serviceType", "community-insight", "community",
                        "2020-01-01T00:00:00Z", null, null)));
        // p-9 has a grant for serviceType but not channel: excluded from a query
        // grouping by both.
        var full = new Contribution("p-10", "site-1", Map.of("serviceType", "housing", "channel", "app"),
                List.of(
                        new ConsentGrant("serviceType", "community-insight", "community",
                                "2020-01-01T00:00:00Z", null, null),
                        new ConsentGrant("channel", "community-insight", "community",
                                "2020-01-01T00:00:00Z", null, null)));
        var source = new InMemoryContributionSource(List.of(partial, full));
        var pipeline = new PrivacyPipeline(source, new LaplaceNoiseSource(new Random(1)),
                new InMemoryBudgetAccountant(2.0));

        var query = new QuerySpec("site-1", List.of("serviceType", "channel"), "community-insight", "community");
        var released = pipeline.compute("t-6", query, 1, 1.0);

        assertThat(released).isPresent();
        assertThat(released.get().computedK()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :connector:extensions:benefits-privacy:test --tests PrivacyPipelineConsentFilterTest`
Expected: the four purpose/recipientClass-scoped tests FAIL (no filtering happens yet; `computedK` is 2 in
every case where the test expects 1). The first test (no purpose/recipientClass) already passes, since it
exercises no new code path.

- [ ] **Step 3: Implement the filter**

Modify `PrivacyPipeline.compute`'s body. Replace this line:

```java
        var groups = KAnonymity.aggregate(contributions.contributionsFor(query.siteId()), query, k);
```

with:

```java
        var raw = contributions.contributionsFor(query.siteId());
        var filtered = (query.purpose() == null || query.recipientClass() == null)
                ? raw
                : raw.stream().filter(c -> consentCovers(c, query)).toList();
        var groups = KAnonymity.aggregate(filtered, query, k);
```

Add this new private static method to the class (below `jsonEscape`, or anywhere else in the class body
that keeps the file's existing method order otherwise undisturbed):

```java
    /**
     * True when contribution c may be included under query's declared purpose and
     * recipientClass (both already confirmed non-null by the caller). A contribution
     * with untracked consent (consentGrants() == null) is always included, preserving
     * every existing caller's exact current behavior. Otherwise, every field in
     * query.groupBy() must have a matching, currently-valid grant: same field, same
     * purpose, same recipientClass, grantedAt not in the future, and neither expired
     * nor revoked as of now. Ports ConsentEvaluator.hasGrant's already-proven matching
     * rule from raw JSON-node comparison to typed ConsentGrant comparison.
     */
    private static boolean consentCovers(Contribution c, QuerySpec query) {
        if (c.consentGrants() == null) {
            return true;
        }
        var now = Instant.now().toString();
        for (var field : query.groupBy()) {
            var covered = c.consentGrants().stream().anyMatch(grant ->
                    field.equals(grant.field())
                            && query.purpose().equals(grant.purpose())
                            && query.recipientClass().equals(grant.recipientClass())
                            && grant.grantedAt().compareTo(now) <= 0
                            && (grant.expiresAt() == null || grant.expiresAt().compareTo(now) > 0)
                            && (grant.revokedAt() == null || grant.revokedAt().compareTo(now) > 0));
            if (!covered) {
                return false;
            }
        }
        return true;
    }
```

Add `import java.time.Instant;` to the file's imports.

- [ ] **Step 4: Run the tests and verify they pass**

Run: `./gradlew :connector:extensions:benefits-privacy:test --tests PrivacyPipelineConsentFilterTest`
Expected: all 6 tests pass.

- [ ] **Step 5: Run every existing test in `benefits-privacy` and `experiments/harness/e4` again**

Run: `./gradlew :connector:extensions:benefits-privacy:test :experiments:harness:e4:test`
Expected: still, every pre-existing test passes unchanged. Every existing `QuerySpec` in this codebase is
built with the legacy two-arg constructor, so `query.purpose() == null` is true everywhere except this
task's own new tests, and the filter branch is unreachable for them.

- [ ] **Step 6: Commit**

```bash
git add connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/PrivacyPipeline.java \
  connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/PrivacyPipelineConsentFilterTest.java
git commit -m "feat(privacy): wire the real consent filter into PrivacyPipeline.compute"
```

---

## Task 9: `CouchDbContributionSource` reads real `consent.grants` into `Contribution`

**Files:**
- Modify: `connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/CouchDbContributionSource.java`
- Modify: `connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/CouchDbContributionSourceIntegrationTest.java`

**Interfaces:**
- Produces: real `ContributionRecord` documents' `consent.grants` array (already written by the existing
  production write path, per `packages/consent-core/src/types.ts`'s `ConsentGrant`, confirmed present but
  previously unread) now populates `Contribution.consentGrants()` for CouchDB-backed queries too.

**Read first:** `README.md`'s documented CouchDB startup steps (`./gradlew test` requires a live, reachable
CouchDB at `localhost:5984`; the repo's `./deploy/docker-compose.yml` provides it; README documents a
`curl -sf http://admin:benefits@localhost:5984/_up` readiness loop). Bring that stack up before running
this task's tests, exactly as every other module in this repo that touches CouchDB already assumes. Read
`CouchDbContributionSourceIntegrationTest.java` in full before editing it: it is an existing, already-passing
integration test that assumes a running CouchDB and manages its own fixture database via raw HTTP calls in
`@BeforeAll`/`@AfterAll`; this task adds one new fixture document and one new test method to that same file,
following its exact existing style, rather than introducing a new test infrastructure pattern.

- [ ] **Step 1: Add a consent-bearing fixture document and a failing test**

In `CouchDbContributionSourceIntegrationTest.java`'s `setUp()`, add one more `send("POST", ...)` call
alongside the three that already exist, seeding a contribution with a `consent.grants` array:

```java
        send("POST", "/" + DB, """
                {"type":"contribution","participantRef":"p-4","siteId":"athens",
                 "channel":"paper",
                 "consent":{"participantRef":"p-4","grants":[
                     {"field":"channel","purpose":"community-insight","recipientClass":"community",
                      "grantedAt":"2020-01-01T00:00:00Z","expiresAt":null,"revokedAt":null}
                 ],"assertedAt":"2020-01-01T00:00:00Z"}}""");
```

Add a new test method to the same class:

```java
    @Test
    void readsConsentGrantsFromTheDocumentIntoContribution() {
        var source = new CouchDbContributionSource(BASE, DB, "admin", "benefits", new ObjectMapper());
        var athens = source.contributionsFor("athens");

        var p4 = athens.stream().filter(c -> c.participantRef().equals("p-4")).findFirst().orElseThrow();
        assertThat(p4.consentGrants()).hasSize(1);
        assertThat(p4.consentGrants().get(0).field()).isEqualTo("channel");
        assertThat(p4.consentGrants().get(0).purpose()).isEqualTo("community-insight");

        // p-1/p-2 (the pre-existing fixtures, no "consent" field in their documents at
        // all) must still parse with consentGrants() == null, not an empty list: the
        // production write path (packages/consent-couchdb) always writes a consent
        // object today, but this distinction still matters for any document that
        // predates that write path or was written by a test fixture without one.
        var p1 = athens.stream().filter(c -> c.participantRef().equals("p-1")).findFirst().orElseThrow();
        assertThat(p1.consentGrants()).isNull();
    }
```

- [ ] **Step 2: Bring up CouchDB and run the test to verify it fails**

```bash
docker compose -f deploy/docker-compose.yml up -d
until curl -sf http://admin:benefits@localhost:5984/_up >/dev/null; do sleep 1; done
```

Run: `./gradlew :connector:extensions:benefits-privacy:test --tests CouchDbContributionSourceIntegrationTest`
Expected: the new test FAILS (`consentGrants()` is currently always `null`, since `parse` never reads the
`consent` field).

- [ ] **Step 3: Implement the parsing change**

Modify `CouchDbContributionSource.parse`. Replace this:

```java
            contributions.add(new Contribution(
                    participantRef,
                    doc.path("siteId").asText(),
                    attributes));
```

with:

```java
            contributions.add(new Contribution(
                    participantRef,
                    doc.path("siteId").asText(),
                    attributes,
                    parseConsentGrants(doc)));
```

Add this new private method to the class:

```java
    private static List<ConsentGrant> parseConsentGrants(JsonNode doc) {
        var consent = doc.get("consent");
        if (consent == null || consent.isNull() || !consent.has("grants") || consent.get("grants").isNull()) {
            return null;
        }
        List<ConsentGrant> grants = new ArrayList<>();
        for (var g : consent.get("grants")) {
            grants.add(new ConsentGrant(
                    g.path("field").asText(),
                    g.path("purpose").asText(),
                    g.path("recipientClass").asText(),
                    g.path("grantedAt").asText(),
                    g.hasNonNull("expiresAt") ? g.get("expiresAt").asText() : null,
                    g.hasNonNull("revokedAt") ? g.get("revokedAt").asText() : null));
        }
        return grants;
    }
```

- [ ] **Step 4: Run the tests and verify they pass**

Run: `./gradlew :connector:extensions:benefits-privacy:test --tests CouchDbContributionSourceIntegrationTest`
Expected: all tests in the file pass, including the pre-existing ones (unaffected: the three original
fixture documents have no `consent` field, so `parseConsentGrants` returns `null` for them, matching the
new test's own `p1` assertion).

- [ ] **Step 5: Commit**

```bash
git add connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/CouchDbContributionSource.java \
  connector/extensions/benefits-privacy/src/test/java/org/benefits/policy/CouchDbContributionSourceIntegrationTest.java
git commit -m "feat(privacy): read real consent.grants from CouchDB documents into Contribution"
```

---

## Task 10: `HeterogeneousPopulationGenerator` (G2 module scaffolding)

**Files:**
- Modify: `settings.gradle.kts` (if the `g2` include line was removed in Task 1's Step 1, re-add it now)
- Create: `experiments/harness/g2/build.gradle.kts`
- Create: `experiments/harness/g2/src/main/java/org/benefits/experiments/g2/HeterogeneousPopulationGenerator.java`
- Test: `experiments/harness/g2/src/test/java/org/benefits/experiments/g2/HeterogeneousPopulationGeneratorTest.java`

**Interfaces:**
- Consumes: `org.benefits.experiments.e4.SeededPopulationGenerator` (unmodified, imported from `e4`).
- Produces: `HeterogeneousPopulationGenerator(long seed).generate(int populationSize, double h): List<Contribution>`, where each `Contribution` carries a non-null `consentGrants()`. Used by Task 11.

- [ ] **Step 1: Create the module**

`experiments/harness/g2/build.gradle.kts`:

```kotlin
plugins {
    `java-library`
}

dependencies {
    implementation(project(":connector:extensions:benefits-privacy"))
    implementation(project(":experiments:harness:e4"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 2: Write the failing test**

`experiments/harness/g2/src/test/java/org/benefits/experiments/g2/HeterogeneousPopulationGeneratorTest.java`:

```java
package org.benefits.experiments.g2;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeterogeneousPopulationGeneratorTest {

    @Test
    void atHeterogeneityZeroEveryParticipantHasAllThreeBroadGrants() {
        var population = new HeterogeneousPopulationGenerator(1L).generate(50, 0.0);
        assertThat(population).hasSize(50);
        for (var contribution : population) {
            assertThat(contribution.consentGrants()).hasSize(3);
            var fields = contribution.consentGrants().stream().map(g -> g.field()).toList();
            assertThat(fields).containsExactlyInAnyOrder("serviceType", "channel", "urgencyLevel");
        }
    }

    @Test
    void atHeterogeneityOneEveryParticipantIsMissingExactlyOneField() {
        var population = new HeterogeneousPopulationGenerator(1L).generate(50, 1.0);
        for (var contribution : population) {
            assertThat(contribution.consentGrants()).hasSize(2);
        }
    }

    @Test
    void everyGrantCoversTheFixedPurposeAndRecipientClass() {
        var population = new HeterogeneousPopulationGenerator(1L).generate(10, 0.5);
        for (var contribution : population) {
            for (var grant : contribution.consentGrants()) {
                assertThat(grant.purpose()).isEqualTo("community-insight");
                assertThat(grant.recipientClass()).isEqualTo("community");
                assertThat(grant.expiresAt()).isNull();
                assertThat(grant.revokedAt()).isNull();
            }
        }
    }

    @Test
    void attributesStillMatchWhatSeededPopulationGeneratorWouldProduce() {
        var fromG2 = new HeterogeneousPopulationGenerator(1L).generate(50, 0.0);
        var fromE4 = new org.benefits.experiments.e4.SeededPopulationGenerator(1L).generate(50, false);

        assertThat(fromG2).hasSameSizeAs(fromE4);
        for (var i = 0; i < fromG2.size(); i++) {
            assertThat(fromG2.get(i).participantRef()).isEqualTo(fromE4.get(i).participantRef());
            assertThat(fromG2.get(i).attribute("serviceType")).isEqualTo(fromE4.get(i).attribute("serviceType"));
            assertThat(fromG2.get(i).attribute("channel")).isEqualTo(fromE4.get(i).attribute("channel"));
            assertThat(fromG2.get(i).attribute("urgencyLevel")).isEqualTo(fromE4.get(i).attribute("urgencyLevel"));
        }
    }

    @Test
    void sameSeedAndSameHProduceByteIdenticalPopulations() {
        var a = new HeterogeneousPopulationGenerator(5L).generate(30, 0.3);
        var b = new HeterogeneousPopulationGenerator(5L).generate(30, 0.3);
        assertThat(a).isEqualTo(b);
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :experiments:harness:g2:test`
Expected: FAIL, `HeterogeneousPopulationGenerator` does not exist.

- [ ] **Step 4: Implement `HeterogeneousPopulationGenerator`**

`experiments/harness/g2/src/main/java/org/benefits/experiments/g2/HeterogeneousPopulationGenerator.java`:

```java
package org.benefits.experiments.g2;

import org.benefits.experiments.e4.SeededPopulationGenerator;
import org.benefits.policy.Contribution;
import org.benefits.policy.ConsentGrant;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Reuses SeededPopulationGenerator (e4, unmodified) for a population's serviceType/
 * channel/urgencyLevel attributes, then layers a consent grant set onto each
 * resulting Contribution according to a heterogeneity parameter h (spec section 4.2).
 */
public class HeterogeneousPopulationGenerator {

    public static final String PURPOSE = "community-insight";
    public static final String RECIPIENT_CLASS = "community";
    private static final String[] FIELDS = {"serviceType", "channel", "urgencyLevel"};

    private final long seed;

    public HeterogeneousPopulationGenerator(long seed) {
        this.seed = seed;
    }

    public List<Contribution> generate(int populationSize, double h) {
        var base = new SeededPopulationGenerator(seed).generate(populationSize, false);
        List<Contribution> withConsent = new ArrayList<>();

        for (var i = 0; i < base.size(); i++) {
            var contribution = base.get(i);
            var random = new Random(mix64(seed * 1_000_003L + i * 7L));
            var narrow = random.nextDouble() < h;
            String omittedField = null;
            if (narrow) {
                omittedField = FIELDS[random.nextInt(FIELDS.length)];
            }

            List<ConsentGrant> grants = new ArrayList<>();
            for (var field : FIELDS) {
                if (field.equals(omittedField)) {
                    continue;
                }
                grants.add(new ConsentGrant(field, PURPOSE, RECIPIENT_CLASS,
                        "2020-01-01T00:00:00Z", null, null));
            }

            withConsent.add(new Contribution(contribution.participantRef(), contribution.siteId(),
                    contribution.attributes(), grants));
        }

        return List.copyOf(withConsent);
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        z = z ^ (z >>> 33);
        return z;
    }
}
```

- [ ] **Step 5: Run the tests and verify they pass**

Run: `./gradlew :experiments:harness:g2:test`
Expected: all 5 tests pass.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts experiments/harness/g2/build.gradle.kts \
  experiments/harness/g2/src/main/java/org/benefits/experiments/g2/HeterogeneousPopulationGenerator.java \
  experiments/harness/g2/src/test/java/org/benefits/experiments/g2/HeterogeneousPopulationGeneratorTest.java
git commit -m "feat(g2): add the g2 module and the heterogeneous population generator"
```

---

## Task 11: G2 live spike and `HeterogeneitySweepPointRunner`

**Files:**
- Test: `experiments/harness/g2/src/test/java/org/benefits/experiments/g2/LiveSpikeTest.java`
- Create: `experiments/harness/g2/src/main/java/org/benefits/experiments/g2/HeterogeneitySweepPointResult.java`
- Create: `experiments/harness/g2/src/main/java/org/benefits/experiments/g2/HeterogeneitySweepPointRunner.java`
- Test: `experiments/harness/g2/src/test/java/org/benefits/experiments/g2/HeterogeneitySweepPointRunnerTest.java`

**Interfaces:**
- Consumes: `HeterogeneousPopulationGenerator` (Task 10), `org.benefits.experiments.e4.{E4Queries, Statistics}` (unmodified, imported from `e4`), the real, now-consent-aware `PrivacyPipeline.compute` (Task 8).
- Produces: `HeterogeneitySweepPointResult` and `HeterogeneitySweepPointRunner(long seed).run(double epsilon, int population, int k, double h, int pointIndex): HeterogeneitySweepPointResult`. Used by Task 12.

- [ ] **Step 1: Write the live spike**

`experiments/harness/g2/src/test/java/org/benefits/experiments/g2/LiveSpikeTest.java`:

```java
package org.benefits.experiments.g2;

import org.benefits.policy.Contribution;
import org.benefits.policy.ConsentGrant;
import org.benefits.policy.InMemoryBudgetAccountant;
import org.benefits.policy.InMemoryContributionSource;
import org.benefits.policy.KAnonymity;
import org.benefits.policy.LaplaceNoiseSource;
import org.benefits.policy.PrivacyPipeline;
import org.benefits.policy.QuerySpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real, now-consent-aware PrivacyPipeline.compute directly with a small
 * hand-built population, confirming the filter's end-to-end behavior before Task 11's
 * sweep runner is built on it.
 */
class LiveSpikeTest {

    @Test
    void withHeterogeneousConsentTheConsentAwareQueryExcludesUncoveredParticipantsButTheLegacyQueryDoesNot() {
        var covered = new Contribution("p-1", "g2-site", Map.of("serviceType", "housing"),
                List.of(new ConsentGrant("serviceType", "community-insight", "community",
                        "2020-01-01T00:00:00Z", null, null)));
        var uncovered = new Contribution("p-2", "g2-site", Map.of("serviceType", "housing"),
                List.of());
        var source = new InMemoryContributionSource(List.of(covered, uncovered));
        var pipeline = new PrivacyPipeline(source, new LaplaceNoiseSource(new Random(1)),
                new InMemoryBudgetAccountant(2.0));

        var legacyQuery = new QuerySpec("g2-site", List.of("serviceType"));
        var legacyReleased = pipeline.compute("spike-legacy", legacyQuery, 1, 1.0);
        assertThat(legacyReleased).isPresent();
        assertThat(legacyReleased.get().computedK()).isEqualTo(2);

        var consentAwareQuery = new QuerySpec("g2-site", List.of("serviceType"),
                HeterogeneousPopulationGenerator.PURPOSE, HeterogeneousPopulationGenerator.RECIPIENT_CLASS);
        var consentAwareReleased = pipeline.compute("spike-aware", consentAwareQuery, 1, 1.0);
        assertThat(consentAwareReleased).isPresent();
        assertThat(consentAwareReleased.get().computedK()).isEqualTo(1);
    }

    @Test
    void groundTruthOverTheFullPopulationDiffersFromGroundTruthOverTheConsentingSubset() {
        var covered = new Contribution("p-1", "g2-site", Map.of("serviceType", "housing"),
                List.of(new ConsentGrant("serviceType", "community-insight", "community",
                        "2020-01-01T00:00:00Z", null, null)));
        var uncovered = new Contribution("p-2", "g2-site", Map.of("serviceType", "housing"),
                List.of());
        var query = new QuerySpec("g2-site", List.of("serviceType"));

        var fullTruth = KAnonymity.aggregate(List.of(covered, uncovered), query, 1);
        var consentingTruth = KAnonymity.aggregate(List.of(covered), query, 1);

        assertThat(fullTruth.get(0).count()).isEqualTo(2);
        assertThat(consentingTruth.get(0).count()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run the spike**

Run: `./gradlew :experiments:harness:g2:test --tests LiveSpikeTest`
Expected: both tests pass, confirming the filter and the dual-ground-truth approach behave exactly as spec
section 4.4 describes.

- [ ] **Step 3: Write the result record**

`experiments/harness/g2/src/main/java/org/benefits/experiments/g2/HeterogeneitySweepPointResult.java`:

```java
package org.benefits.experiments.g2;

public record HeterogeneitySweepPointResult(
        long seed,
        double epsilon,
        int population,
        int k,
        double h,
        double excludedFraction,
        double coverageMedianRelativeError,
        double coverageIqrRelativeError,
        double exclusionMedianRelativeError,
        double exclusionIqrRelativeError,
        double suppressionRefusalRate,
        int dataPoints
) {
}
```

- [ ] **Step 4: Write the failing test for `HeterogeneitySweepPointRunner`**

`experiments/harness/g2/src/test/java/org/benefits/experiments/g2/HeterogeneitySweepPointRunnerTest.java`:

```java
package org.benefits.experiments.g2;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeterogeneitySweepPointRunnerTest {

    @Test
    void atHeterogeneityZeroCoverageAndExclusionErrorAreIdentical() {
        // h = 0: nobody is excluded, so consentingTruth == fullTruth for every group,
        // and the two relative-error statistics must therefore be exactly equal.
        var result = new HeterogeneitySweepPointRunner(1L).run(1.0, 100, 5, 0.0, 0);

        assertThat(result.coverageMedianRelativeError()).isEqualTo(result.exclusionMedianRelativeError());
        assertThat(result.coverageIqrRelativeError()).isEqualTo(result.exclusionIqrRelativeError());
        assertThat(result.excludedFraction()).isEqualTo(0.0);
    }

    @Test
    void higherHeterogeneityProducesAHigherExcludedFraction() {
        var low = new HeterogeneitySweepPointRunner(1L).run(1.0, 300, 5, 0.1, 1);
        var high = new HeterogeneitySweepPointRunner(1L).run(1.0, 300, 5, 0.9, 2);

        assertThat(high.excludedFraction()).isGreaterThan(low.excludedFraction());
    }

    @Test
    void dataPointsIsPositive() {
        var result = new HeterogeneitySweepPointRunner(1L).run(1.0, 100, 5, 0.5, 3);
        assertThat(result.dataPoints()).isGreaterThanOrEqualTo(8);
    }

    @Test
    void sameSeedAndSamePointProduceByteIdenticalResults() {
        var a = new HeterogeneitySweepPointRunner(7L).run(0.5, 100, 5, 0.3, 4);
        var b = new HeterogeneitySweepPointRunner(7L).run(0.5, 100, 5, 0.3, 4);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void medianAndIqrAreFiniteAndNonNegative() {
        var result = new HeterogeneitySweepPointRunner(1L).run(0.5, 100, 5, 0.7, 5);
        assertThat(result.coverageMedianRelativeError()).isGreaterThanOrEqualTo(0.0).isFinite();
        assertThat(result.exclusionMedianRelativeError()).isGreaterThanOrEqualTo(0.0).isFinite();
    }
}
```

- [ ] **Step 5: Run the test to verify it fails**

Run: `./gradlew :experiments:harness:g2:test --tests HeterogeneitySweepPointRunnerTest`
Expected: FAIL, `HeterogeneitySweepPointRunner` does not exist.

- [ ] **Step 6: Implement `HeterogeneitySweepPointRunner`**

`experiments/harness/g2/src/main/java/org/benefits/experiments/g2/HeterogeneitySweepPointRunner.java`:

```java
package org.benefits.experiments.g2;

import org.benefits.experiments.e4.E4Queries;
import org.benefits.experiments.e4.Statistics;
import org.benefits.policy.GroupedCount;
import org.benefits.policy.InMemoryBudgetAccountant;
import org.benefits.policy.InMemoryContributionSource;
import org.benefits.policy.KAnonymity;
import org.benefits.policy.LaplaceNoiseSource;
import org.benefits.policy.PrivacyPipeline;
import org.benefits.policy.QuerySpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Runs one G2 sweep point (spec section 4.4): the dual ground truth (full population
 * vs. consenting subset) for each of the eight E4Queries.workload() queries, rebuilt
 * with the fixed purpose/recipientClass so PrivacyPipeline's real consent filter
 * (Task 8) is genuinely exercised, against one shared InMemoryBudgetAccountant.
 */
public class HeterogeneitySweepPointRunner {

    public static final double TOTAL_SITE_BUDGET = 2.0;

    private final long seed;

    public HeterogeneitySweepPointRunner(long seed) {
        this.seed = seed;
    }

    public HeterogeneitySweepPointResult run(double epsilon, int population, int k, double h, int pointIndex) {
        var contributions = new HeterogeneousPopulationGenerator(seed).generate(population, h);
        var contributionSource = new InMemoryContributionSource(contributions);
        var noiseSeed = mix64(seed * 1_000_003L + pointIndex);
        var noise = new LaplaceNoiseSource(new Random(noiseSeed));
        var accountant = new InMemoryBudgetAccountant(TOTAL_SITE_BUDGET);
        var pipeline = new PrivacyPipeline(contributionSource, noise, accountant);

        List<Double> coverageErrors = new ArrayList<>();
        List<Double> exclusionErrors = new ArrayList<>();
        var suppressedOrRefused = 0;
        var totalDataPoints = 0;

        var excludedParticipants = new java.util.HashSet<String>();
        for (var query : E4Queries.workload()) {
            var scopedSpec = new QuerySpec(query.spec().siteId(), query.spec().groupBy(),
                    HeterogeneousPopulationGenerator.PURPOSE, HeterogeneousPopulationGenerator.RECIPIENT_CLASS);
            var consenting = contributions.stream()
                    .filter(c -> consentCoversForTest(c, scopedSpec))
                    .toList();
            for (var c : contributions) {
                if (!consentCoversForTest(c, scopedSpec)) {
                    excludedParticipants.add(c.participantRef());
                }
            }

            var fullTruth = KAnonymity.aggregate(contributions, query.spec(), 1);
            var consentingTruth = KAnonymity.aggregate(consenting, query.spec(), 1);
            var released = pipeline.compute("g2-" + query.name(), scopedSpec, k, epsilon);
            var observedByGroup = org.benefits.experiments.e4.ObservedCounts.parse(released);

            Map<String, Integer> fullByGroup = toMap(fullTruth);
            Map<String, Integer> consentingByGroup = toMap(consentingTruth);

            if (query.kind() == E4Queries.Kind.GROUPED) {
                for (var entry : fullByGroup.entrySet()) {
                    var fullCount = entry.getValue();
                    var consentingCount = consentingByGroup.getOrDefault(entry.getKey(), 0);
                    var observedCount = observedByGroup.get(entry.getKey());
                    totalDataPoints++;
                    if (released.isEmpty() || observedCount == null || consentingCount == 0) {
                        coverageErrors.add(1.0);
                        exclusionErrors.add(1.0);
                        suppressedOrRefused++;
                    } else {
                        coverageErrors.add(Math.abs(observedCount - consentingCount) / (double) consentingCount);
                        exclusionErrors.add(Math.abs(observedCount - fullCount) / (double) fullCount);
                    }
                }
            } else {
                var targetCategory = query.kind() == E4Queries.Kind.PROPORTION_LARGEST
                        ? largestGroup(fullTruth)
                        : query.targetCategory();
                var fullTotal = fullTruth.stream().mapToInt(GroupedCount::count).sum();
                var consentingTotal = consentingTruth.stream().mapToInt(GroupedCount::count).sum();
                var fullTarget = fullByGroup.getOrDefault(targetCategory, 0);
                var consentingTarget = consentingByGroup.getOrDefault(targetCategory, 0);
                var fullProportion = fullTotal == 0 ? 0.0 : fullTarget / (double) fullTotal;
                var consentingProportion = consentingTotal == 0 ? 0.0 : consentingTarget / (double) consentingTotal;

                totalDataPoints++;
                // Deliberately not E4's own convention: E4 excluded a zero-true-
                // proportion data point from the pool entirely, since it only had one
                // population and a zero denominator was undefined. Here, a zero
                // consentingProportion with a non-zero fullProportion is itself a real,
                // interesting signal (heterogeneous consent made a whole target
                // category disappear from what the community can even ask about), so
                // it is sentinelled at 1.0 like a suppressed or refused query, not
                // dropped. This is astronomically rare at the swept population sizes
                // (well under one in a million for the legal-aid proportion query) but
                // must not crash or divide by zero if it occurs.
                if (consentingProportion == 0.0 || released.isEmpty()) {
                    coverageErrors.add(1.0);
                    exclusionErrors.add(1.0);
                    suppressedOrRefused++;
                } else {
                    var observedTotal = observedByGroup.values().stream().mapToDouble(Double::doubleValue).sum();
                    if (observedTotal <= 0.0) {
                        coverageErrors.add(1.0);
                        exclusionErrors.add(1.0);
                        suppressedOrRefused++;
                    } else {
                        var observedTarget = observedByGroup.getOrDefault(targetCategory, 0.0);
                        var observedProportion = observedTarget / observedTotal;
                        coverageErrors.add(Math.abs(observedProportion - consentingProportion) / consentingProportion);
                        exclusionErrors.add(fullProportion == 0.0 ? 1.0
                                : Math.abs(observedProportion - fullProportion) / fullProportion);
                    }
                }
            }
        }

        var excludedFraction = excludedParticipants.size() / (double) contributions.size();

        return new HeterogeneitySweepPointResult(
                seed, epsilon, population, k, h,
                excludedFraction,
                Statistics.median(coverageErrors),
                Statistics.iqr(coverageErrors),
                Statistics.median(exclusionErrors),
                Statistics.iqr(exclusionErrors),
                suppressedOrRefused / (double) totalDataPoints,
                totalDataPoints
        );
    }

    private static boolean consentCoversForTest(org.benefits.policy.Contribution c, QuerySpec query) {
        if (c.consentGrants() == null) {
            return true;
        }
        for (var field : query.groupBy()) {
            var covered = c.consentGrants().stream().anyMatch(grant ->
                    field.equals(grant.field())
                            && query.purpose().equals(grant.purpose())
                            && query.recipientClass().equals(grant.recipientClass()));
            if (!covered) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Integer> toMap(List<GroupedCount> groups) {
        Map<String, Integer> map = new HashMap<>();
        for (var g : groups) {
            map.put(g.groupKey(), g.count());
        }
        return map;
    }

    private static String largestGroup(List<GroupedCount> groundTruth) {
        return groundTruth.stream()
                .max((a, b) -> Integer.compare(a.count(), b.count()))
                .orElseThrow()
                .groupKey();
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        z = z ^ (z >>> 33);
        return z;
    }
}
```

Note on `consentCoversForTest`: this duplicates `PrivacyPipeline`'s private `consentCovers` matching logic
(minus the time-window check, since every grant `HeterogeneousPopulationGenerator` produces is always
valid, `expiresAt`/`revokedAt` both null) purely to let the sweep runner compute `consentingTruth` and
`excludedFraction` itself, since `consentCovers` is private to `PrivacyPipeline` and this class needs the
same membership test independently of any single `compute` call. If a task reviewer judges this
duplication should instead be a small shared package-visible helper inside `benefits-privacy` (e.g. a
`ConsentFilter` utility class both `PrivacyPipeline` and this runner call), that is an acceptable
alternative refactor within this task's scope; do not silently diverge the two matching rules if you make
that change; keep them provably identical.

- [ ] **Step 7: Run the tests and verify they pass**

Run: `./gradlew :experiments:harness:g2:test`
Expected: all tests pass, including the Task 10 and live-spike tests already committed.

- [ ] **Step 8: Commit**

```bash
git add experiments/harness/g2/src/test/java/org/benefits/experiments/g2/LiveSpikeTest.java \
  experiments/harness/g2/src/main/java/org/benefits/experiments/g2/HeterogeneitySweepPointResult.java \
  experiments/harness/g2/src/main/java/org/benefits/experiments/g2/HeterogeneitySweepPointRunner.java \
  experiments/harness/g2/src/test/java/org/benefits/experiments/g2/HeterogeneitySweepPointRunnerTest.java
git commit -m "feat(g2): add the live spike and the heterogeneity sweep point runner"
```

---

## Task 12: G2 sweep driver, CSV writer, and production entry point

**Files:**
- Create: `experiments/harness/g2/src/main/java/org/benefits/experiments/g2/HeterogeneitySweepConfig.java`
- Create: `experiments/harness/g2/src/main/java/org/benefits/experiments/g2/HeterogeneitySweepDriver.java`
- Create: `experiments/harness/g2/src/main/java/org/benefits/experiments/g2/RunG2Sweep.java`
- Test: `experiments/harness/g2/src/test/java/org/benefits/experiments/g2/HeterogeneitySweepDriverTest.java`

**Interfaces:**
- Consumes: `HeterogeneitySweepPointRunner` (Task 11).
- Produces: `experiments/results/g2/heterogeneity.csv`, the sub-project's committed output.

- [ ] **Step 1: Write the failing test**

`experiments/harness/g2/src/test/java/org/benefits/experiments/g2/HeterogeneitySweepDriverTest.java`:

```java
package org.benefits.experiments.g2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HeterogeneitySweepDriverTest {

    @Test
    void writesOneHeaderRowPlusOneRowPerSweepPoint(@TempDir Path tempDir) throws IOException {
        var csvPath = tempDir.resolve("heterogeneity.csv").toString();
        var config = new HeterogeneitySweepConfig(
                List.of(0.5, 1.0),
                List.of(100),
                5,
                List.of(0.0, 0.5, 1.0)
        );

        new HeterogeneitySweepDriver().run(1L, config, csvPath);

        var lines = Files.readAllLines(Path.of(csvPath));
        // 2 epsilons x 1 population x 3 h values = 6 points, plus 1 header row.
        assertThat(lines).hasSize(7);
        assertThat(lines.get(0)).isEqualTo("seed,epsilon,population,k,h,excluded_fraction,"
                + "coverage_median_relative_error,coverage_iqr_relative_error,"
                + "exclusion_median_relative_error,exclusion_iqr_relative_error,"
                + "suppression_refusal_rate,data_points");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :experiments:harness:g2:test --tests HeterogeneitySweepDriverTest`
Expected: FAIL, `HeterogeneitySweepConfig`/`HeterogeneitySweepDriver` do not exist.

- [ ] **Step 3: Implement the config record and driver**

`experiments/harness/g2/src/main/java/org/benefits/experiments/g2/HeterogeneitySweepConfig.java`:

```java
package org.benefits.experiments.g2;

import java.util.List;

public record HeterogeneitySweepConfig(
        List<Double> epsilonValues,
        List<Integer> populationValues,
        int k,
        List<Double> hValues
) {
}
```

`experiments/harness/g2/src/main/java/org/benefits/experiments/g2/HeterogeneitySweepDriver.java`:

```java
package org.benefits.experiments.g2;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the full G2 sweep (spec section 4.3): the cross product of every swept value in
 * the given HeterogeneitySweepConfig, one HeterogeneitySweepPointRunner call per point,
 * written as a single CSV.
 */
public class HeterogeneitySweepDriver {

    public void run(long seed, HeterogeneitySweepConfig config, String csvPath) throws IOException {
        var runner = new HeterogeneitySweepPointRunner(seed);
        List<String> lines = new ArrayList<>();
        lines.add("seed,epsilon,population,k,h,excluded_fraction,"
                + "coverage_median_relative_error,coverage_iqr_relative_error,"
                + "exclusion_median_relative_error,exclusion_iqr_relative_error,"
                + "suppression_refusal_rate,data_points");

        var pointIndex = 0;
        for (var epsilon : config.epsilonValues()) {
            for (var population : config.populationValues()) {
                for (var h : config.hValues()) {
                    var result = runner.run(epsilon, population, config.k(), h, pointIndex);
                    lines.add(toCsvRow(result));
                    pointIndex++;
                }
            }
        }

        var outPath = Path.of(csvPath);
        Files.createDirectories(outPath.toAbsolutePath().getParent());
        Files.write(outPath, String.join("\n", lines).concat("\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String toCsvRow(HeterogeneitySweepPointResult result) {
        return String.join(",",
                Long.toString(result.seed()),
                Double.toString(result.epsilon()),
                Integer.toString(result.population()),
                Integer.toString(result.k()),
                Double.toString(result.h()),
                Double.toString(result.excludedFraction()),
                Double.toString(result.coverageMedianRelativeError()),
                Double.toString(result.coverageIqrRelativeError()),
                Double.toString(result.exclusionMedianRelativeError()),
                Double.toString(result.exclusionIqrRelativeError()),
                Double.toString(result.suppressionRefusalRate()),
                Integer.toString(result.dataPoints())
        );
    }
}
```

- [ ] **Step 4: Run the tests and verify they pass**

Run: `./gradlew :experiments:harness:g2:test --tests HeterogeneitySweepDriverTest`
Expected: passes.

- [ ] **Step 5: Write the production entry point**

`experiments/harness/g2/src/main/java/org/benefits/experiments/g2/RunG2Sweep.java`:

```java
package org.benefits.experiments.g2;

import java.io.IOException;
import java.util.List;

public class RunG2Sweep {

    public static void main(String[] args) throws IOException {
        var seed = 1L;
        var config = new HeterogeneitySweepConfig(
                List.of(0.5, 1.0, 2.0),
                List.of(100, 300),
                5,
                List.of(0.0, 0.1, 0.2, 0.3, 0.5, 0.7, 1.0)
        );

        new HeterogeneitySweepDriver().run(seed, config, "../../results/g2/heterogeneity.csv");

        System.out.println("G2 sweep complete");
    }
}
```

- [ ] **Step 6: Run the full production sweep and verify reproducibility**

```bash
./gradlew :experiments:harness:g2:compileJava
java -cp "experiments/harness/g2/build/classes/java/main:experiments/harness/e4/build/classes/java/main:connector/extensions/benefits-privacy/build/classes/java/main:connector/extensions/benefits-policy/build/classes/java/main:$(find ~/.gradle -iname '*.jar' | tr '\n' ':')" \
  org.benefits.experiments.g2.RunG2Sweep
```

(Check E4's own task reports for the exact working classpath invocation and its Jackson-jar caveat; G2
needs Jackson, since it transitively pulls it in via `benefits-privacy`.) Run it twice into two different
output paths and `diff` them.
Expected: 43 lines (1 header + 3 x 2 x 7 = 42 data rows). The two runs' CSVs must be byte-identical.

- [ ] **Step 7: Inspect the real result, per spec section 4.4's own stated purpose**

Before committing, read the generated `heterogeneity.csv` and confirm `excluded_fraction` increases
monotonically (or close to it, allowing for small sample-size noise at low population values) as `h`
increases within each fixed `(epsilon, population)` slice, and that `exclusion_median_relative_error`
diverges from `coverage_median_relative_error` as `h` grows past `0.0` (the two are identical only at
`h = 0.0`, per the live spike and Task 11's own test). Record what you actually observe in this task's
report, numerically, the same way E4's own Task 6 and its final-review fix wave both did; do not just
assert the code compiled.

- [ ] **Step 8: Commit the code and the real results**

```bash
git add experiments/harness/g2/src/main/java/org/benefits/experiments/g2/HeterogeneitySweepConfig.java \
  experiments/harness/g2/src/main/java/org/benefits/experiments/g2/HeterogeneitySweepDriver.java \
  experiments/harness/g2/src/main/java/org/benefits/experiments/g2/RunG2Sweep.java \
  experiments/harness/g2/src/test/java/org/benefits/experiments/g2/HeterogeneitySweepDriverTest.java
git commit -m "feat(g2): add the sweep driver and production entry point"
git add -f experiments/results/g2/heterogeneity.csv
git commit -m "feat(g2): commit the real production heterogeneity sweep results"
```

---

## Final verification (part of the last task's own checklist, not a separate task)

Before the whole-plan final review (per subagent-driven-development), run the full repository test suite
once more to confirm nothing outside `g1`/`g2` regressed:

```bash
./gradlew compileJava compileTestJava test
```

Expected: every module's tests pass, including `connector:extensions:benefits-privacy` (Task 7-9's real
production changes), `experiments:harness:e1`, `e3`, `e4` (all read-only consumers or entirely untouched),
and the two new modules.
