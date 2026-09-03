# E4 Experiment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and run the E4 (privacy budget vs. utility) experiment, sweeping epsilon, site population, k, and population skew over a shared-budget eight-query workload, producing a seeded, reproducible CSV of median/IQR relative error and suppression/refusal rate per sweep point.

**Architecture:** A new Gradle module (`experiments/harness/e4`) drives the existing, unmodified in-memory privacy pipeline (`PrivacyPipeline`, `KAnonymity`, `LaplaceNoiseSource`, `InMemoryBudgetAccountant`, `InMemoryContributionSource` in `connector/extensions/benefits-privacy`) with a seeded synthetic population and eight fixed queries run in order against one shared per-point budget accountant, comparing each query's noised/suppressed output against an unsuppressed, unnoised ground truth computed with the same production `KAnonymity.aggregate` call at `k=1`.

**Tech Stack:** Java, Gradle, JUnit 5, AssertJ, Jackson (already a transitive dependency of `benefits-privacy`, declared directly here since it's imported directly).

**Spec:** `docs/superpowers/specs/2026-09-02-e4-experiment-design.md`

## Global Constraints

- No em-dashes in code, docs, or commits.
- No LLM/AI-tooling attribution in commits.
- No emojis in code or logs.
- All randomness must come from `java.util.Random(seed)`, never `Math.random()`/`ThreadLocalRandom`.
- `connector/extensions/benefits-privacy`'s production classes (`PrivacyPipeline`, `KAnonymity`, `LaplaceNoiseSource`, `InMemoryBudgetAccountant`, `InMemoryContributionSource`, `QuerySpec`, `Contribution`, `GroupedCount`, `ReleaseArtifact`, `NoiseSource`, `BudgetAccountant`, `ContributionSource`) must not be modified.
- No sums or means over raw values; only grouped counts and values derived from grouped counts (proportions), matching `QuerySpec`'s own deliberate scope.
- Total site budget per sweep point is a fixed constant, 2.0, not swept.

---

## Task 1: Gradle module and seeded population generator

**Files:**
- Create: `experiments/harness/e4/build.gradle.kts`
- Modify: `settings.gradle.kts`
- Create: `experiments/harness/e4/src/main/java/org/benefits/experiments/e4/SeededPopulationGenerator.java`
- Test: `experiments/harness/e4/src/test/java/org/benefits/experiments/e4/SeededPopulationGeneratorTest.java`

**Interfaces:**
- Produces: `SeededPopulationGenerator(long seed)`, `List<Contribution> generate(int populationSize, boolean skewed)`, `public static final String SITE_ID`. Tasks 2, 4, 5 consume `SITE_ID`; Task 4 consumes `generate`.

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
include(":experiments:harness:e4")
```

- [ ] **Step 2: Create the module's build file**

Create `experiments/harness/e4/build.gradle.kts`:

```kotlin
plugins {
    `java-library`
}

dependencies {
    implementation(project(":connector:extensions:benefits-privacy"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.0")
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
```

The Jackson version matches `connector/extensions/benefits-privacy/build.gradle.kts`'s own pin exactly, since that dependency is already transitively present through the `benefits-privacy` project dependency; declaring it directly here is for code that imports it directly (Task 4's JSON parsing), not a new version choice.

- [ ] **Step 3: Write the failing test for the population generator**

Create `experiments/harness/e4/src/test/java/org/benefits/experiments/e4/SeededPopulationGeneratorTest.java`:

```java
package org.benefits.experiments.e4;

import org.benefits.policy.Contribution;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SeededPopulationGeneratorTest {

    @Test
    void sameSeedProducesIdenticalPopulations() {
        var a = new SeededPopulationGenerator(42).generate(100, false);
        var b = new SeededPopulationGenerator(42).generate(100, false);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void differentSeedsProduceDifferentPopulations() {
        var a = new SeededPopulationGenerator(42).generate(100, false);
        var b = new SeededPopulationGenerator(43).generate(100, false);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void uniformAndSkewedDifferForTheSameSeed() {
        var uniform = new SeededPopulationGenerator(42).generate(300, false);
        var skewed = new SeededPopulationGenerator(42).generate(300, true);
        assertThat(uniform).isNotEqualTo(skewed);
    }

    @Test
    void producesTheRequestedPopulationSize() {
        var population = new SeededPopulationGenerator(1).generate(37, false);
        assertThat(population).hasSize(37);
    }

    @Test
    void everyContributionHasAllThreeAttributesAndTheSharedSiteId() {
        var population = new SeededPopulationGenerator(1).generate(50, false);
        for (var contribution : population) {
            assertThat(contribution.siteId()).isEqualTo(SeededPopulationGenerator.SITE_ID);
            assertThat(contribution.attribute("serviceType")).isNotEqualTo(Contribution.ABSENT);
            assertThat(contribution.attribute("channel")).isNotEqualTo(Contribution.ABSENT);
            assertThat(contribution.attribute("urgencyLevel")).isNotEqualTo(Contribution.ABSENT);
        }
    }

    @Test
    void everyContributionHasADistinctParticipantRef() {
        var population = new SeededPopulationGenerator(1).generate(200, false);
        var distinctRefs = population.stream().map(Contribution::participantRef).collect(Collectors.toSet());
        assertThat(distinctRefs).hasSize(200);
    }

    @Test
    void skewedChannelLeansTowardPaperAcrossALargePopulation() {
        var population = new SeededPopulationGenerator(1).generate(10000, true);
        var paperCount = population.stream().filter(c -> "paper".equals(c.attribute("channel"))).count();
        // 61% target; a large population keeps sampling noise small relative to that margin.
        assertThat(paperCount).isGreaterThan(5000L);
    }

    @Test
    void uniformChannelIsRoughlyEvenAcrossALargePopulation() {
        var population = new SeededPopulationGenerator(1).generate(10000, false);
        var counts = new java.util.HashMap<String, Long>();
        for (var contribution : population) {
            counts.merge(contribution.attribute("channel"), 1L, Long::sum);
        }
        assertThat(counts).hasSize(4);
        for (var count : counts.values()) {
            assertThat(count).isBetween(2000L, 3000L);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `./gradlew :experiments:harness:e4:test --tests "org.benefits.experiments.e4.SeededPopulationGeneratorTest"`
Expected: FAIL to compile, `SeededPopulationGenerator` does not exist.

- [ ] **Step 5: Implement the population generator**

Create `experiments/harness/e4/src/main/java/org/benefits/experiments/e4/SeededPopulationGenerator.java`:

```java
package org.benefits.experiments.e4;

import org.benefits.policy.Contribution;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Generates a synthetic community population deterministically from a seed, population
 * size, and skew mode (spec section 3). A fresh java.util.Random is constructed per call
 * from a derived seed, not reused as a field, so the result depends only on (seed,
 * populationSize, skewed), never on call order.
 */
public class SeededPopulationGenerator {

    public static final String SITE_ID = "e4-sweep-site";

    private static final String[] SERVICE_TYPES =
            {"housing", "food-assistance", "healthcare", "legal-aid", "employment"};
    private static final String[] CHANNELS = {"app", "sms", "paper", "voice"};
    private static final String[] URGENCY_LEVELS = {"routine", "urgent", "emergency"};

    // Cumulative probability boundaries, one per category in the arrays above, summing
    // to 1.0. housing 50%, remaining four service types splitting the other 50% evenly.
    private static final double[] SERVICE_TYPE_SKEW = {0.50, 0.625, 0.75, 0.875, 1.0};
    // paper 61% (the workshop statistic), remaining 39% split evenly across app/sms/voice.
    private static final double[] CHANNEL_SKEW = {0.13, 0.26, 0.87, 1.0};
    // routine 60%, remaining 40% split evenly between urgent and emergency.
    private static final double[] URGENCY_SKEW = {0.60, 0.80, 1.0};

    private final long seed;

    public SeededPopulationGenerator(long seed) {
        this.seed = seed;
    }

    public List<Contribution> generate(int populationSize, boolean skewed) {
        var random = new Random(derivedSeed(populationSize, skewed));
        List<Contribution> contributions = new ArrayList<>();
        for (var i = 0; i < populationSize; i++) {
            Map<String, String> attributes = new HashMap<>();
            attributes.put("serviceType", pick(random, SERVICE_TYPES, skewed ? SERVICE_TYPE_SKEW : null));
            attributes.put("channel", pick(random, CHANNELS, skewed ? CHANNEL_SKEW : null));
            attributes.put("urgencyLevel", pick(random, URGENCY_LEVELS, skewed ? URGENCY_SKEW : null));
            contributions.add(new Contribution("synthetic-participant-" + seed + "-" + i, SITE_ID, attributes));
        }
        return List.copyOf(contributions);
    }

    private static String pick(Random random, String[] categories, double[] cumulative) {
        var draw = random.nextDouble();
        if (cumulative == null) {
            var index = (int) (draw * categories.length);
            return categories[Math.min(index, categories.length - 1)];
        }
        for (var i = 0; i < cumulative.length; i++) {
            if (draw < cumulative[i]) {
                return categories[i];
            }
        }
        return categories[categories.length - 1];
    }

    private long derivedSeed(int populationSize, boolean skewed) {
        return seed * 1_000_003L + populationSize * 2L + (skewed ? 1 : 0);
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :experiments:harness:e4:test --tests "org.benefits.experiments.e4.SeededPopulationGeneratorTest"`
Expected: PASS, all eight cases green.

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts experiments/harness/e4/build.gradle.kts \
  experiments/harness/e4/src/main/java/org/benefits/experiments/e4/SeededPopulationGenerator.java \
  experiments/harness/e4/src/test/java/org/benefits/experiments/e4/SeededPopulationGeneratorTest.java
git commit -m "feat(e4): add the experiments harness Gradle module and seeded population generator"
```

---

## Task 2: Live spike against the real privacy pipeline, and the eight queries

**Files:**
- Create: `experiments/harness/e4/src/main/java/org/benefits/experiments/e4/E4Queries.java`
- Test: `experiments/harness/e4/src/test/java/org/benefits/experiments/e4/LiveSpikeTest.java`

**Interfaces:**
- Consumes: `SeededPopulationGenerator.SITE_ID` (Task 1).
- Produces: `E4Queries.Kind` enum (`GROUPED`, `PROPORTION_LARGEST`, `PROPORTION_TARGET`), `E4Queries.E4Query` record (`name`, `spec`, `kind`, `targetCategory`), `E4Queries.RARE_SERVICE_TYPE`, `E4Queries.workload(): List<E4Query>`. Task 4 consumes `workload()`.

This task is the live-code-verification spike the spec requires before building the full sweep (spec section 10): confirm the real `PrivacyPipeline`/`KAnonymity`/`LaplaceNoiseSource`/`InMemoryBudgetAccountant` classes behave exactly as this plan's later tasks assume, against real synthetic data, before writing the workload runner that depends on that behavior.

- [ ] **Step 1: Write the live spike test**

Create `experiments/harness/e4/src/test/java/org/benefits/experiments/e4/LiveSpikeTest.java`:

```java
package org.benefits.experiments.e4;

import org.benefits.policy.Contribution;
import org.benefits.policy.InMemoryBudgetAccountant;
import org.benefits.policy.InMemoryContributionSource;
import org.benefits.policy.KAnonymity;
import org.benefits.policy.LaplaceNoiseSource;
import org.benefits.policy.PrivacyPipeline;
import org.benefits.policy.QuerySpec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real, unmodified privacy pipeline classes directly with synthetic data,
 * confirming the behavior this plan's later tasks depend on, before building on it.
 */
class LiveSpikeTest {

    private static List<Contribution> contributionsWithGroupSizes(int belowK, int atOrAboveK) {
        List<Contribution> contributions = new ArrayList<>();
        for (var i = 0; i < belowK; i++) {
            Map<String, String> attrs = new HashMap<>();
            attrs.put("serviceType", "legal-aid");
            contributions.add(new Contribution("p-small-" + i, "spike-site", attrs));
        }
        for (var i = 0; i < atOrAboveK; i++) {
            Map<String, String> attrs = new HashMap<>();
            attrs.put("serviceType", "housing");
            contributions.add(new Contribution("p-large-" + i, "spike-site", attrs));
        }
        return contributions;
    }

    @Test
    void aGroupAtOrAboveKSurvivesAndIsNoised() {
        var contributions = contributionsWithGroupSizes(0, 10);
        var source = new InMemoryContributionSource(contributions);
        var noise = new LaplaceNoiseSource(new Random(1));
        var accountant = new InMemoryBudgetAccountant(2.0);
        var pipeline = new PrivacyPipeline(source, noise, accountant);

        var released = pipeline.compute("spike-1", new QuerySpec("spike-site", List.of("serviceType")), 5, 1.0);

        assertThat(released).isPresent();
        assertThat(released.get().computedK()).isEqualTo(10);
        var json = new String(released.get().bytes());
        assertThat(json).contains("housing");
    }

    @Test
    void aGroupBelowKIsSuppressedEntirely() {
        var contributions = contributionsWithGroupSizes(3, 0);
        var source = new InMemoryContributionSource(contributions);
        var noise = new LaplaceNoiseSource(new Random(1));
        var accountant = new InMemoryBudgetAccountant(2.0);
        var pipeline = new PrivacyPipeline(source, noise, accountant);

        var released = pipeline.compute("spike-2", new QuerySpec("spike-site", List.of("serviceType")), 5, 1.0);

        assertThat(released).isPresent();
        var json = new String(released.get().bytes());
        assertThat(json).doesNotContain("legal-aid");
    }

    @Test
    void budgetExhaustionRefusesAQueryAndReservesNothing() {
        var contributions = contributionsWithGroupSizes(0, 10);
        var source = new InMemoryContributionSource(contributions);
        var noise = new LaplaceNoiseSource(new Random(1));
        var accountant = new InMemoryBudgetAccountant(1.0);
        var pipeline = new PrivacyPipeline(source, noise, accountant);
        var query = new QuerySpec("spike-site", List.of("serviceType"));

        var first = pipeline.compute("spike-3a", query, 5, 0.8);
        assertThat(first).isPresent();
        assertThat(accountant.remaining("spike-site")).isEqualTo(0.2);

        var second = pipeline.compute("spike-3b", query, 5, 0.8);
        assertThat(second).isEmpty();
        assertThat(accountant.remaining("spike-site")).isEqualTo(0.2);
    }

    @Test
    void kAnonymityAggregateAtKOneNeverSuppressesAndGivesTrueCounts() {
        var contributions = contributionsWithGroupSizes(2, 10);
        var groups = KAnonymity.aggregate(contributions, new QuerySpec("spike-site", List.of("serviceType")), 1);

        var byKey = new HashMap<String, Integer>();
        for (var g : groups) {
            byKey.put(g.groupKey(), g.count());
        }
        assertThat(byKey).containsEntry("legal-aid", 2);
        assertThat(byKey).containsEntry("housing", 10);
    }

    @Test
    void releasedJsonShapeIsParseableGroupsArrayOfGroupAndCount() throws Exception {
        var contributions = contributionsWithGroupSizes(0, 6);
        var source = new InMemoryContributionSource(contributions);
        var noise = new LaplaceNoiseSource(new Random(1));
        var accountant = new InMemoryBudgetAccountant(2.0);
        var pipeline = new PrivacyPipeline(source, noise, accountant);

        var released = pipeline.compute("spike-4", new QuerySpec("spike-site", List.of("serviceType")), 5, 1.0);
        assertThat(released).isPresent();

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = mapper.readTree(released.get().bytes());
        assertThat(root.has("groups")).isTrue();
        assertThat(root.get("groups").isArray()).isTrue();
        var first = root.get("groups").get(0);
        assertThat(first.has("group")).isTrue();
        assertThat(first.has("count")).isTrue();
    }
}
```

- [ ] **Step 2: Run the spike**

Run: `./gradlew :experiments:harness:e4:test --tests "org.benefits.experiments.e4.LiveSpikeTest"`
Expected: FAIL to compile at first (no test infrastructure issue expected, but confirm), then once compiling, all five cases PASS. If any assumption here does not hold against the real classes (for example, if the JSON shape differs, or budget accounting behaves differently than expected), stop and reconcile this task with what the real classes actually do before proceeding to Task 4, which depends on these exact behaviors.

- [ ] **Step 3: Write the eight queries**

Create `experiments/harness/e4/src/main/java/org/benefits/experiments/e4/E4Queries.java`:

```java
package org.benefits.experiments.e4;

import org.benefits.policy.QuerySpec;

import java.util.List;

/**
 * The eight fixed community queries (spec section 4), in the exact order they run
 * against one site's shared budget (spec section 6). Order matters: budget exhaustion
 * depends on it.
 */
public final class E4Queries {

    private E4Queries() {
    }

    public enum Kind { GROUPED, PROPORTION_LARGEST, PROPORTION_TARGET }

    public record E4Query(String name, QuerySpec spec, Kind kind, String targetCategory) {
    }

    public static final String RARE_SERVICE_TYPE = "legal-aid";

    private static final QuerySpec SERVICE_TYPE =
            new QuerySpec(SeededPopulationGenerator.SITE_ID, List.of("serviceType"));
    private static final QuerySpec CHANNEL =
            new QuerySpec(SeededPopulationGenerator.SITE_ID, List.of("channel"));
    private static final QuerySpec URGENCY =
            new QuerySpec(SeededPopulationGenerator.SITE_ID, List.of("urgencyLevel"));
    private static final QuerySpec SERVICE_TYPE_BY_CHANNEL =
            new QuerySpec(SeededPopulationGenerator.SITE_ID, List.of("serviceType", "channel"));
    private static final QuerySpec SERVICE_TYPE_BY_URGENCY =
            new QuerySpec(SeededPopulationGenerator.SITE_ID, List.of("serviceType", "urgencyLevel"));
    private static final QuerySpec SERVICE_TYPE_BY_CHANNEL_BY_URGENCY =
            new QuerySpec(SeededPopulationGenerator.SITE_ID, List.of("serviceType", "channel", "urgencyLevel"));

    public static List<E4Query> workload() {
        return List.of(
                new E4Query("count-service-type", SERVICE_TYPE, Kind.GROUPED, null),
                new E4Query("count-channel", CHANNEL, Kind.GROUPED, null),
                new E4Query("count-urgency", URGENCY, Kind.GROUPED, null),
                new E4Query("proportion-largest-service-type", SERVICE_TYPE, Kind.PROPORTION_LARGEST, null),
                new E4Query("proportion-legal-aid", SERVICE_TYPE, Kind.PROPORTION_TARGET, RARE_SERVICE_TYPE),
                new E4Query("crosstab-service-type-channel", SERVICE_TYPE_BY_CHANNEL, Kind.GROUPED, null),
                new E4Query("crosstab-service-type-urgency", SERVICE_TYPE_BY_URGENCY, Kind.GROUPED, null),
                new E4Query("crosstab-service-type-channel-urgency", SERVICE_TYPE_BY_CHANNEL_BY_URGENCY,
                        Kind.GROUPED, null)
        );
    }
}
```

- [ ] **Step 4: Write a test confirming the workload shape**

Create `experiments/harness/e4/src/test/java/org/benefits/experiments/e4/E4QueriesTest.java`:

```java
package org.benefits.experiments.e4;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class E4QueriesTest {

    @Test
    void workloadHasExactlyEightQueriesInTheSpecifiedOrder() {
        var workload = E4Queries.workload();
        assertThat(workload).hasSize(8);
        assertThat(workload.stream().map(E4Queries.E4Query::name)).containsExactly(
                "count-service-type",
                "count-channel",
                "count-urgency",
                "proportion-largest-service-type",
                "proportion-legal-aid",
                "crosstab-service-type-channel",
                "crosstab-service-type-urgency",
                "crosstab-service-type-channel-urgency"
        );
    }

    @Test
    void threeCountQueriesGroupByExactlyOneAttribute() {
        var workload = E4Queries.workload();
        assertThat(workload.get(0).spec().groupBy()).containsExactly("serviceType");
        assertThat(workload.get(1).spec().groupBy()).containsExactly("channel");
        assertThat(workload.get(2).spec().groupBy()).containsExactly("urgencyLevel");
    }

    @Test
    void threeCrosstabQueriesGroupByIncreasingAttributeCounts() {
        var workload = E4Queries.workload();
        assertThat(workload.get(5).spec().groupBy()).containsExactly("serviceType", "channel");
        assertThat(workload.get(6).spec().groupBy()).containsExactly("serviceType", "urgencyLevel");
        assertThat(workload.get(7).spec().groupBy()).containsExactly("serviceType", "channel", "urgencyLevel");
    }

    @Test
    void proportionQueriesAreMarkedWithTheCorrectKind() {
        var workload = E4Queries.workload();
        assertThat(workload.get(3).kind()).isEqualTo(E4Queries.Kind.PROPORTION_LARGEST);
        assertThat(workload.get(4).kind()).isEqualTo(E4Queries.Kind.PROPORTION_TARGET);
        assertThat(workload.get(4).targetCategory()).isEqualTo("legal-aid");
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :experiments:harness:e4:test --tests "org.benefits.experiments.e4.E4QueriesTest"`
Expected: PASS, all four cases green.

- [ ] **Step 6: Commit**

```bash
git add experiments/harness/e4/src/main/java/org/benefits/experiments/e4/E4Queries.java \
  experiments/harness/e4/src/test/java/org/benefits/experiments/e4/LiveSpikeTest.java \
  experiments/harness/e4/src/test/java/org/benefits/experiments/e4/E4QueriesTest.java
git commit -m "feat(e4): add the live spike against the real privacy pipeline and the eight-query workload"
```

---

## Task 3: Statistics and observed-count parsing

**Files:**
- Create: `experiments/harness/e4/src/main/java/org/benefits/experiments/e4/Statistics.java`
- Create: `experiments/harness/e4/src/main/java/org/benefits/experiments/e4/ObservedCounts.java`
- Test: `experiments/harness/e4/src/test/java/org/benefits/experiments/e4/StatisticsTest.java`
- Test: `experiments/harness/e4/src/test/java/org/benefits/experiments/e4/ObservedCountsTest.java`

**Interfaces:**
- Produces: `Statistics.median(List<Double>): double`, `Statistics.iqr(List<Double>): double`, `Statistics.percentile(List<Double>, double p): double`. `ObservedCounts.parse(Optional<ReleaseArtifact>): Map<String, Double>`. Task 4 consumes both.

- [ ] **Step 1: Write the failing test for statistics**

Create `experiments/harness/e4/src/test/java/org/benefits/experiments/e4/StatisticsTest.java`:

```java
package org.benefits.experiments.e4;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StatisticsTest {

    @Test
    void medianOfAnOddCountIsTheMiddleValue() {
        assertThat(Statistics.median(List.of(1.0, 3.0, 2.0))).isEqualTo(2.0);
    }

    @Test
    void medianOfAnEvenCountAveragesTheTwoMiddleValues() {
        assertThat(Statistics.median(List.of(1.0, 2.0, 3.0, 4.0))).isEqualTo(2.5);
    }

    @Test
    void medianOfASingleValueIsThatValue() {
        assertThat(Statistics.median(List.of(7.0))).isEqualTo(7.0);
    }

    @Test
    void iqrOfAUniformZeroToOneHundredSampleIsFifty() {
        List<Double> values = new java.util.ArrayList<>();
        for (var i = 0; i <= 100; i++) {
            values.add((double) i);
        }
        assertThat(Statistics.iqr(values)).isCloseTo(50.0, within(0.5));
    }

    @Test
    void percentileRejectsAnEmptyList() {
        assertThrows(IllegalArgumentException.class, () -> Statistics.median(List.of()));
    }

    @Test
    void medianIsUnaffectedByInputOrder() {
        var sorted = Statistics.median(List.of(1.0, 2.0, 3.0, 4.0, 5.0));
        var shuffled = Statistics.median(List.of(5.0, 1.0, 4.0, 2.0, 3.0));
        assertThat(sorted).isEqualTo(shuffled);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :experiments:harness:e4:test --tests "org.benefits.experiments.e4.StatisticsTest"`
Expected: FAIL to compile, `Statistics` does not exist.

- [ ] **Step 3: Implement statistics**

Create `experiments/harness/e4/src/main/java/org/benefits/experiments/e4/Statistics.java`:

```java
package org.benefits.experiments.e4;

import java.util.List;

/**
 * Median and interquartile range via linear interpolation between order statistics
 * (the same convention as NumPy's default and R's type 7), applied to the pooled
 * relative-error values one sweep point produces (spec section 7).
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

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :experiments:harness:e4:test --tests "org.benefits.experiments.e4.StatisticsTest"`
Expected: PASS, all six cases green.

- [ ] **Step 5: Write the failing test for observed-count parsing**

Create `experiments/harness/e4/src/test/java/org/benefits/experiments/e4/ObservedCountsTest.java`:

```java
package org.benefits.experiments.e4;

import org.benefits.policy.Contribution;
import org.benefits.policy.InMemoryBudgetAccountant;
import org.benefits.policy.InMemoryContributionSource;
import org.benefits.policy.LaplaceNoiseSource;
import org.benefits.policy.PrivacyPipeline;
import org.benefits.policy.QuerySpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ObservedCountsTest {

    @Test
    void emptyOptionalParsesToAnEmptyMap() {
        assertThat(ObservedCounts.parse(Optional.empty())).isEmpty();
    }

    @Test
    void parsesARealReleaseArtifactIntoGroupKeyToCountMap() {
        List<Contribution> contributions = new ArrayList<>();
        for (var i = 0; i < 8; i++) {
            Map<String, String> attrs = new HashMap<>();
            attrs.put("serviceType", "housing");
            contributions.add(new Contribution("p-" + i, "obs-site", attrs));
        }
        var source = new InMemoryContributionSource(contributions);
        var noise = new LaplaceNoiseSource(new Random(1));
        var accountant = new InMemoryBudgetAccountant(2.0);
        var pipeline = new PrivacyPipeline(source, noise, accountant);

        var released = pipeline.compute("obs-1", new QuerySpec("obs-site", List.of("serviceType")), 5, 1.0);
        var counts = ObservedCounts.parse(released);

        assertThat(counts).containsKey("housing");
        assertThat(counts.get("housing")).isCloseTo(8.0, org.assertj.core.api.Assertions.within(20.0));
    }
}
```

- [ ] **Step 6: Run test to verify it fails**

Run: `./gradlew :experiments:harness:e4:test --tests "org.benefits.experiments.e4.ObservedCountsTest"`
Expected: FAIL to compile, `ObservedCounts` does not exist.

- [ ] **Step 7: Implement observed-count parsing**

Create `experiments/harness/e4/src/main/java/org/benefits/experiments/e4/ObservedCounts.java`:

```java
package org.benefits.experiments.e4;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.benefits.policy.ReleaseArtifact;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Parses a PrivacyPipeline.compute() result's JSON bytes (the {"groups":[{"group":...,
 * "count":...}]} shape PrivacyPipeline itself produces) into a group-key-to-noised-count
 * map. An empty Optional (a refused query) parses to an empty map, matching "this query
 * produced no data" uniformly regardless of whether the whole query was refused or a
 * specific group was suppressed.
 */
public final class ObservedCounts {

    private ObservedCounts() {
    }

    public static Map<String, Double> parse(Optional<ReleaseArtifact> released) {
        if (released.isEmpty()) {
            return Map.of();
        }
        try {
            var mapper = new ObjectMapper();
            var root = mapper.readTree(released.get().bytes());
            Map<String, Double> result = new HashMap<>();
            for (var groupNode : root.get("groups")) {
                result.put(groupNode.get("group").asText(), groupNode.get("count").asDouble());
            }
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("failed to parse release artifact JSON", e);
        }
    }
}
```

- [ ] **Step 8: Run test to verify it passes**

Run: `./gradlew :experiments:harness:e4:test --tests "org.benefits.experiments.e4.ObservedCountsTest"`
Expected: PASS, both cases green.

- [ ] **Step 9: Commit**

```bash
git add experiments/harness/e4/src/main/java/org/benefits/experiments/e4/Statistics.java \
  experiments/harness/e4/src/main/java/org/benefits/experiments/e4/ObservedCounts.java \
  experiments/harness/e4/src/test/java/org/benefits/experiments/e4/StatisticsTest.java \
  experiments/harness/e4/src/test/java/org/benefits/experiments/e4/ObservedCountsTest.java
git commit -m "feat(e4): add median/IQR statistics and release-artifact JSON parsing"
```

---

## Task 4: The sweep-point runner

**Files:**
- Create: `experiments/harness/e4/src/main/java/org/benefits/experiments/e4/SweepPointResult.java`
- Create: `experiments/harness/e4/src/main/java/org/benefits/experiments/e4/SweepPointRunner.java`
- Test: `experiments/harness/e4/src/test/java/org/benefits/experiments/e4/SweepPointRunnerTest.java`

**Interfaces:**
- Consumes: `SeededPopulationGenerator` (Task 1), `E4Queries.workload()` (Task 2), `Statistics.median`/`Statistics.iqr` (Task 3), `ObservedCounts.parse` (Task 3).
- Produces: `SweepPointResult` record (`seed`, `epsilon`, `population`, `k`, `skewed`, `medianRelativeError`, `iqrRelativeError`, `suppressionRefusalRate`, `dataPoints`), `SweepPointRunner(long seed)`, `SweepPointResult run(double epsilon, int population, int k, boolean skewed, int epsilonIndex, int populationIndex, int kIndex, int skewIndex)`. Task 5 consumes both.

- [ ] **Step 1: Write the result record**

Create `experiments/harness/e4/src/main/java/org/benefits/experiments/e4/SweepPointResult.java`:

```java
package org.benefits.experiments.e4;

public record SweepPointResult(
        long seed,
        double epsilon,
        int population,
        int k,
        boolean skewed,
        double medianRelativeError,
        double iqrRelativeError,
        double suppressionRefusalRate,
        int dataPoints
) {
}
```

- [ ] **Step 2: Write the failing test for the runner**

Create `experiments/harness/e4/src/test/java/org/benefits/experiments/e4/SweepPointRunnerTest.java`:

```java
package org.benefits.experiments.e4;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SweepPointRunnerTest {

    @Test
    void producesTheRequestedSweepPointFields() {
        var result = new SweepPointRunner(1).run(1.0, 100, 5, false, 2, 1, 1, 0);
        assertThat(result.seed()).isEqualTo(1);
        assertThat(result.epsilon()).isEqualTo(1.0);
        assertThat(result.population()).isEqualTo(100);
        assertThat(result.k()).isEqualTo(5);
        assertThat(result.skewed()).isFalse();
    }

    @Test
    void medianAndIqrAreWithinTheBoundedZeroToOneRange() {
        var result = new SweepPointRunner(1).run(0.5, 100, 5, false, 1, 1, 1, 0);
        assertThat(result.medianRelativeError()).isBetween(0.0, 1.0);
        assertThat(result.iqrRelativeError()).isBetween(0.0, 1.0);
        assertThat(result.suppressionRefusalRate()).isBetween(0.0, 1.0);
    }

    @Test
    void dataPointsIsPositiveAndReflectsThePooledGroupAndProportionCount() {
        var result = new SweepPointRunner(1).run(1.0, 300, 3, false, 2, 3, 0, 0);
        // At least one data point per query in the eight-query workload.
        assertThat(result.dataPoints()).isGreaterThanOrEqualTo(8);
    }

    @Test
    void sameSeedAndSamePointProduceByteIdenticalResults() {
        var a = new SweepPointRunner(7).run(0.5, 200, 5, true, 1, 2, 1, 1);
        var b = new SweepPointRunner(7).run(0.5, 200, 5, true, 1, 2, 1, 1);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void aHighEpsilonWithASmallPopulationProducesSomeRefusalOrSuppression() {
        // At epsilon=2.0, the shared 2.0 total budget (spec section 6) allows only the
        // first query to succeed; the remaining seven are refused outright, so the
        // suppression/refusal rate must be well above zero regardless of population or k.
        var result = new SweepPointRunner(1).run(2.0, 30, 10, false, 4, 0, 3, 0);
        assertThat(result.suppressionRefusalRate()).isGreaterThan(0.5);
    }

    @Test
    void aLowEpsilonAnswersEveryQueryWithNoRefusals() {
        // At epsilon=0.1, the shared 2.0 total budget covers all eight queries
        // (8 x 0.1 = 0.8 < 2.0), so nothing is refused for budget reasons; a large
        // population and low k also keep suppression unlikely, isolating the refusal
        // component of the statistic at (close to) zero.
        var result = new SweepPointRunner(1).run(0.1, 300, 3, false, 0, 3, 0, 0);
        assertThat(result.suppressionRefusalRate()).isLessThan(0.2);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :experiments:harness:e4:test --tests "org.benefits.experiments.e4.SweepPointRunnerTest"`
Expected: FAIL to compile, `SweepPointRunner` does not exist.

- [ ] **Step 4: Implement the sweep-point runner**

Create `experiments/harness/e4/src/main/java/org/benefits/experiments/e4/SweepPointRunner.java`:

```java
package org.benefits.experiments.e4;

import org.benefits.policy.GroupedCount;
import org.benefits.policy.InMemoryBudgetAccountant;
import org.benefits.policy.InMemoryContributionSource;
import org.benefits.policy.KAnonymity;
import org.benefits.policy.LaplaceNoiseSource;
import org.benefits.policy.PrivacyPipeline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Runs one sweep point: generates the seeded population, runs the eight-query workload
 * (E4Queries.workload()) in order against one shared InMemoryBudgetAccountant (spec
 * section 6, total budget fixed at TOTAL_SITE_BUDGET), and pools every relative-error
 * data point the workload produces into that point's utility statistics (spec section 7).
 */
public class SweepPointRunner {

    public static final double TOTAL_SITE_BUDGET = 2.0;

    private final long seed;

    public SweepPointRunner(long seed) {
        this.seed = seed;
    }

    public SweepPointResult run(double epsilon, int population, int k, boolean skewed,
                                 int epsilonIndex, int populationIndex, int kIndex, int skewIndex) {
        var contributions = new SeededPopulationGenerator(seed).generate(population, skewed);
        var contributionSource = new InMemoryContributionSource(contributions);
        var noiseSeed = pointSeed(epsilonIndex, populationIndex, kIndex, skewIndex);
        var noise = new LaplaceNoiseSource(new Random(noiseSeed));
        var accountant = new InMemoryBudgetAccountant(TOTAL_SITE_BUDGET);
        var pipeline = new PrivacyPipeline(contributionSource, noise, accountant);

        List<Double> relativeErrors = new ArrayList<>();
        var suppressedOrRefused = 0;

        for (var query : E4Queries.workload()) {
            var groundTruth = KAnonymity.aggregate(contributions, query.spec(), 1);
            Map<String, Integer> trueByGroup = new HashMap<>();
            for (var g : groundTruth) {
                trueByGroup.put(g.groupKey(), g.count());
            }

            var released = pipeline.compute("e4-" + query.name(), query.spec(), k, epsilon);
            var observedByGroup = ObservedCounts.parse(released);

            if (query.kind() == E4Queries.Kind.GROUPED) {
                for (var entry : trueByGroup.entrySet()) {
                    var trueCount = entry.getValue();
                    var observedCount = observedByGroup.get(entry.getKey());
                    if (released.isEmpty() || observedCount == null) {
                        relativeErrors.add(1.0);
                        suppressedOrRefused++;
                    } else {
                        relativeErrors.add(Math.abs(observedCount - trueCount) / (double) trueCount);
                    }
                }
            } else {
                var targetCategory = query.kind() == E4Queries.Kind.PROPORTION_LARGEST
                        ? largestGroup(groundTruth)
                        : query.targetCategory();
                var trueTotal = groundTruth.stream().mapToInt(GroupedCount::count).sum();
                var trueTarget = trueByGroup.getOrDefault(targetCategory, 0);
                var trueProportion = trueTarget / (double) trueTotal;

                if (released.isEmpty()) {
                    relativeErrors.add(1.0);
                    suppressedOrRefused++;
                } else {
                    var observedTotal = observedByGroup.values().stream().mapToDouble(Double::doubleValue).sum();
                    if (observedTotal == 0.0) {
                        relativeErrors.add(1.0);
                        suppressedOrRefused++;
                    } else {
                        var observedTarget = observedByGroup.getOrDefault(targetCategory, 0.0);
                        var observedProportion = observedTarget / observedTotal;
                        relativeErrors.add(Math.abs(observedProportion - trueProportion) / trueProportion);
                    }
                }
            }
        }

        return new SweepPointResult(
                seed, epsilon, population, k, skewed,
                Statistics.median(relativeErrors),
                Statistics.iqr(relativeErrors),
                suppressedOrRefused / (double) relativeErrors.size(),
                relativeErrors.size()
        );
    }

    private static String largestGroup(List<GroupedCount> groundTruth) {
        return groundTruth.stream()
                .max((a, b) -> Integer.compare(a.count(), b.count()))
                .orElseThrow()
                .groupKey();
    }

    private long pointSeed(int epsilonIndex, int populationIndex, int kIndex, int skewIndex) {
        var combined = epsilonIndex;
        combined = combined * 5 + populationIndex;
        combined = combined * 4 + kIndex;
        combined = combined * 2 + skewIndex;
        return seed * 1_000_003L + combined;
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :experiments:harness:e4:test --tests "org.benefits.experiments.e4.SweepPointRunnerTest"`
Expected: PASS, all six cases green. If the high-epsilon or low-epsilon refusal-rate assertions fail, re-check the exact index-to-value mapping used in the test against the swept value arrays Task 5 defines (epsilonIndex 4 must correspond to epsilon=2.0, epsilonIndex 0 to epsilon=0.1) before assuming the runner itself is wrong.

- [ ] **Step 6: Commit**

```bash
git add experiments/harness/e4/src/main/java/org/benefits/experiments/e4/SweepPointResult.java \
  experiments/harness/e4/src/main/java/org/benefits/experiments/e4/SweepPointRunner.java \
  experiments/harness/e4/src/test/java/org/benefits/experiments/e4/SweepPointRunnerTest.java
git commit -m "feat(e4): add the sweep-point runner combining population, workload, and utility computation"
```

---

## Task 5: CSV writer and full sweep driver

**Files:**
- Create: `experiments/harness/e4/src/main/java/org/benefits/experiments/e4/RunE4Sweep.java`
- Test: `experiments/harness/e4/src/test/java/org/benefits/experiments/e4/RunE4SweepTest.java`

**Interfaces:**
- Consumes: `SweepPointRunner` (Task 4), `SweepPointResult` (Task 4).
- Produces: `SweepConfig` record (the five swept-value arrays), `E4SweepDriver.run(long seed, SweepConfig config, String csvPath)`, `RunE4Sweep.main(String[])` (the production entry point). Task 6 invokes `RunE4Sweep.main`.

- [ ] **Step 1: Write the failing test for the sweep driver**

Create `experiments/harness/e4/src/test/java/org/benefits/experiments/e4/RunE4SweepTest.java`:

```java
package org.benefits.experiments.e4;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunE4SweepTest {

    @Test
    void writesOneRowPerSweepPointWithTheExpectedHeader(@TempDir Path tempDir) throws IOException {
        var csvPath = tempDir.resolve("utility.csv");
        var config = new SweepConfig(
                List.of(0.5, 1.0),
                List.of(30, 100),
                List.of(3, 5),
                List.of(false, true)
        );

        new E4SweepDriver().run(1, config, csvPath.toString());

        var lines = Files.readAllLines(csvPath);
        assertThat(lines.get(0)).isEqualTo(
                "seed,epsilon,population,k,skew,median_relative_error,iqr_relative_error,"
                        + "suppression_refusal_rate,data_points");
        // 2 epsilon x 2 population x 2 k x 2 skew = 16 points, plus header.
        assertThat(lines).hasSize(17);
    }

    @Test
    void skewColumnIsHumanReadableUniformOrSkewed(@TempDir Path tempDir) throws IOException {
        var csvPath = tempDir.resolve("utility.csv");
        var config = new SweepConfig(List.of(1.0), List.of(100), List.of(5), List.of(false, true));

        new E4SweepDriver().run(1, config, csvPath.toString());

        var content = Files.readString(csvPath);
        assertThat(content).contains(",uniform,");
        assertThat(content).contains(",skewed,");
    }

    @Test
    void isReproducibleForTheSameSeed(@TempDir Path tempDir) throws IOException {
        var csvA = tempDir.resolve("a.csv");
        var csvB = tempDir.resolve("b.csv");
        var config = new SweepConfig(List.of(0.5, 1.5), List.of(30, 200), List.of(3, 7), List.of(false, true));

        new E4SweepDriver().run(9, config, csvA.toString());
        new E4SweepDriver().run(9, config, csvB.toString());

        assertThat(Files.readString(csvA)).isEqualTo(Files.readString(csvB));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :experiments:harness:e4:test --tests "org.benefits.experiments.e4.RunE4SweepTest"`
Expected: FAIL to compile, `SweepConfig`/`E4SweepDriver` do not exist.

- [ ] **Step 3: Implement the sweep config, driver, and CSV writer**

Create `experiments/harness/e4/src/main/java/org/benefits/experiments/e4/SweepConfig.java`:

```java
package org.benefits.experiments.e4;

import java.util.List;

public record SweepConfig(
        List<Double> epsilonValues,
        List<Integer> populationValues,
        List<Integer> kValues,
        List<Boolean> skewValues
) {
}
```

Create `experiments/harness/e4/src/main/java/org/benefits/experiments/e4/E4SweepDriver.java`:

```java
package org.benefits.experiments.e4;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the full sweep (spec section 5): the cross product of every swept value in the
 * given SweepConfig, one SweepPointRunner call per point, written as a single CSV.
 */
public class E4SweepDriver {

    public void run(long seed, SweepConfig config, String csvPath) throws IOException {
        var runner = new SweepPointRunner(seed);
        List<String> lines = new ArrayList<>();
        lines.add("seed,epsilon,population,k,skew,median_relative_error,iqr_relative_error,"
                + "suppression_refusal_rate,data_points");

        for (var epsilonIndex = 0; epsilonIndex < config.epsilonValues().size(); epsilonIndex++) {
            var epsilon = config.epsilonValues().get(epsilonIndex);
            for (var populationIndex = 0; populationIndex < config.populationValues().size(); populationIndex++) {
                var population = config.populationValues().get(populationIndex);
                for (var kIndex = 0; kIndex < config.kValues().size(); kIndex++) {
                    var k = config.kValues().get(kIndex);
                    for (var skewIndex = 0; skewIndex < config.skewValues().size(); skewIndex++) {
                        var skewed = config.skewValues().get(skewIndex);
                        var result = runner.run(epsilon, population, k, skewed,
                                epsilonIndex, populationIndex, kIndex, skewIndex);
                        lines.add(toCsvRow(result));
                    }
                }
            }
        }

        var outPath = Path.of(csvPath);
        Files.createDirectories(outPath.toAbsolutePath().getParent());
        Files.write(outPath, String.join("\n", lines).concat("\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String toCsvRow(SweepPointResult result) {
        return String.join(",",
                Long.toString(result.seed()),
                Double.toString(result.epsilon()),
                Integer.toString(result.population()),
                Integer.toString(result.k()),
                result.skewed() ? "skewed" : "uniform",
                Double.toString(result.medianRelativeError()),
                Double.toString(result.iqrRelativeError()),
                Double.toString(result.suppressionRefusalRate()),
                Integer.toString(result.dataPoints())
        );
    }
}
```

Create `experiments/harness/e4/src/main/java/org/benefits/experiments/e4/RunE4Sweep.java`:

```java
package org.benefits.experiments.e4;

import java.io.IOException;
import java.util.List;

public class RunE4Sweep {

    public static void main(String[] args) throws IOException {
        var seed = 1L;
        var config = new SweepConfig(
                List.of(0.1, 0.5, 1.0, 1.5, 2.0),
                List.of(30, 100, 200, 300),
                List.of(3, 5, 7, 10),
                List.of(false, true)
        );

        new E4SweepDriver().run(seed, config, "../../results/e4/utility.csv");

        System.out.println("E4 sweep complete");
    }
}
```

Port and path convention matches `RunE3Fixtures.java`: relative to the JVM's working directory, which must be `experiments/harness/e4` when this runs (see Task 6).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :experiments:harness:e4:test --tests "org.benefits.experiments.e4.RunE4SweepTest"`
Expected: PASS, all three cases green.

- [ ] **Step 5: Run the full module test suite**

Run: `./gradlew :experiments:harness:e4:test`
Expected: PASS, every test in the module green (all prior tasks' tests plus this one).

- [ ] **Step 6: Commit**

```bash
git add experiments/harness/e4/src/main/java/org/benefits/experiments/e4/SweepConfig.java \
  experiments/harness/e4/src/main/java/org/benefits/experiments/e4/E4SweepDriver.java \
  experiments/harness/e4/src/main/java/org/benefits/experiments/e4/RunE4Sweep.java \
  experiments/harness/e4/src/test/java/org/benefits/experiments/e4/RunE4SweepTest.java
git commit -m "feat(e4): add the CSV writer and full sweep driver across all 160 points"
```

---

## Task 6: Reproducibility verification

**Files:**
- No new source files. This task runs the full 160-point production sweep twice and diffs the output, directly verifying this plan's own gate: "results reproduce from seed."

- [ ] **Step 1: Build the module**

Run: `./gradlew :experiments:harness:e4:testClasses`

- [ ] **Step 2: Run the full sweep twice from the correct working directory**

`RunE4Sweep`'s output path (`../../results/e4/utility.csv`) is relative to the JVM's working directory, exactly like `RunE3Fixtures` (spec-adjacent precedent, section 2). Run with `experiments/harness/e4` as the working directory:

```bash
cd /home/und3rd06012/benefits
./gradlew :experiments:harness:e4:testClasses :connector:extensions:benefits-privacy:jar
cd experiments/harness/e4
java -cp "build/classes/java/main:../../../connector/extensions/benefits-privacy/build/libs/*" \
  org.benefits.experiments.e4.RunE4Sweep
cp ../../results/e4/utility.csv /tmp/e4-utility-run-a.csv
java -cp "build/classes/java/main:../../../connector/extensions/benefits-privacy/build/libs/*" \
  org.benefits.experiments.e4.RunE4Sweep
diff /tmp/e4-utility-run-a.csv ../../results/e4/utility.csv
cd /home/und3rd06012/benefits
```

Expected: the `diff` produces no output. Unlike E1's wall-clock-timing columns, E4 has no non-deterministic column at all (no timing, no live infrastructure): every column, including `median_relative_error`/`iqr_relative_error`/`suppression_refusal_rate`, must be byte-identical between the two runs. Any difference here is a real defect, not expected noise, and needs a real root cause before proceeding.

- [ ] **Step 3: Sanity-check the output**

Run: `wc -l experiments/results/e4/utility.csv`
Expected: 161 lines (160 data rows + header), matching 5 epsilon values x 4 population values x 4 k values x 2 skew values.

Run: `grep ",skewed," experiments/results/e4/utility.csv | wc -l` and `grep ",uniform," experiments/results/e4/utility.csv | wc -l`
Expected: 80 each.

- [ ] **Step 4: Record the result**

If the diff in Step 2 is not empty, stop and investigate as a real bug (this directly contradicts the plan's own gate). If it is empty and Step 3's counts match, this task's verification is the deliverable; there is nothing further to commit beyond a note in the final report confirming the gate was checked and passed. `experiments/results/e4/utility.csv` (a real, seeded production run at this point) is left in place for a later, controller-level decision on whether to commit it, matching how E1/E3's own results were handled.

---

## Final Verification

After all 6 tasks are complete:

1. `./gradlew build` succeeds, including `:experiments:harness:e4`.
2. `./gradlew test` succeeds, including every new test class in this module.
3. Task 6's reproducibility check passes with an empty diff.
4. `experiments/results/e4/utility.csv` exists, has 161 lines, and its `skew` column contains exactly 80 `uniform` and 80 `skewed` rows.
5. Nothing under `connector/extensions/benefits-privacy` was modified.
