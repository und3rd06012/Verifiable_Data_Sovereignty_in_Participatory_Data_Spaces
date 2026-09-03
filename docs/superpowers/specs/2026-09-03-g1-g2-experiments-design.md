# G1+G2 Experiments: Design

## 1. Purpose and scope

The third and final phase-7 experiment sub-project (after E1+E3-full and E4,
both complete). Per the parent spec (section 5), this covers two paired
experiments:

- **G1**: quorum availability and deadlock. How the real multi-steward
  quorum design behaves as stewards go offline, are slow, or conflict.
- **G2**: consent heterogeneity versus aggregate utility. How a
  community's aggregate query utility changes as participants' individual
  consent choices become more heterogeneous rather than uniform.

Both are simulations driving real, existing production classes rather than
reimplementing their logic. G1 drives the real steward-quorum machinery
under a simulated timeline of steward availability. G2 requires a real
production capability that does not exist today (consent gating query-time
inclusion, not just writes) and adds it, then sweeps a heterogeneity
parameter against the real, now-consent-aware pipeline.

Both are architecturally independent of each other and of E1/E3/E4; they
share this one spec because the parent spec named them as a pair, not
because they interact. Read them as two self-contained halves.

## 2. Repository layout

Two new Gradle modules, alongside `experiments/harness/e1`, `e3`, `e4`:

- `experiments:harness:g1` (new) — depends on
  `connector:extensions:benefits-policy` (the module that already owns
  `StewardQuorumFunction`, `StewardAuthorization`, `StewardSignature`,
  `StewardVeto`, `InMemoryStewardAuthorizationStore`, and transitively
  exposes the EDC policy-engine/contract-spi/participant-spi types as
  `api` dependencies, so no new EDC coordinates are needed).
- `experiments:harness:g2` (new) — depends on
  `connector:extensions:benefits-privacy` (this spec's own changes to
  `QuerySpec`/`Contribution`/`PrivacyPipeline`, section 4.1) and on
  `experiments:harness:e4` (to reuse `SeededPopulationGenerator`,
  `E4Queries`, `Statistics` rather than duplicate them).

Both follow the existing `java-library` module convention (see
`experiments/harness/e4/build.gradle.kts`): `implementation(project(...))`
for the dependencies above, `testImplementation(libs.junit.jupiter)` +
`testImplementation(libs.assertj.core)` + `testRuntimeOnly(libs.junit.platform.launcher)`,
`tasks.test { useJUnitPlatform() }`.

Results: `experiments/results/g1/availability.csv`,
`experiments/results/g2/heterogeneity.csv`.

## 3. G1: quorum availability and deadlock

### 3.1 What's real and what's hypothetical

**`StewardQuorumFunction`** (`connector/extensions/benefits-policy/.../StewardQuorumFunction.java`)
is the real, unmodified production class making the actual quorum
decision: `evaluate(Operator, Object rightValue, Permission rule, ContractNegotiationPolicyContext context)`
reads `required` off `rightValue`, looks up the release request's
`StewardAuthorization` from a `StewardAuthorizationStore`, and returns
`authorization.distinctStewardCount() >= required`. It never reads
`.vetoes()`. `StewardQuorumFunctionTest.java` already proves this class is
callable directly, with no EDC runtime, exactly the way this spec's harness
will call it: a plain `new StewardQuorumFunction(store, monitor)`, a
`Permission` built via `Permission.Builder` with a
`RELEASE_REQUEST_KEY` sibling constraint, and a
`new ContractNegotiationPolicyContext(new ParticipantAgent("consumer", Map.of(), Map.of()))`.
G1's harness constructs these the same way, on every tick, against a real
`InMemoryStewardAuthorizationStore` whose `StewardAuthorization` the
harness updates as simulated stewards act. **Neither `StewardQuorumFunction`
nor `InMemoryStewardAuthorizationStore` is modified.**

Alongside it, **Arm B** runs a second, harness-only decision function,
`HypotheticalVetoBlocksQuorumFunction` (new, lives in
`experiments/harness/g1`, not in `benefits-policy`, and its Javadoc says so
explicitly): `signed >= required && authorization.vetoes().isEmpty()`.
This does not reflect real production behavior (vetoes never gate
anything today, confirmed by grep across every non-test caller of
`.vetoes()`); it exists purely to show, on the identical simulated
timeline, how the availability/deadlock picture would differ if vetoes
were wired in to block release the way an intuitive reading of "veto"
suggests they should.

### 3.2 Simulated timeline

One discrete-tick simulation per run. State: a pool of `N` stewards, each
either not-yet-acted, signed, or vetoed (a steward acts at most once —
signs or vetoes, never both; this is a deliberate simplification of the
real model, where nothing technically prevents both, made to keep the
timeline model tractable, noted here rather than silently assumed).

Each tick, for every not-yet-acted steward independently: with
probability `p_act` they act this tick; if they act, with probability
`veto_share` the action is a veto, otherwise a signature. (`p_act` models
"is this steward available/engaged this tick"; `veto_share` models "of the
stewards who do act, what fraction object rather than approve.")
Newly-signed stewards are appended to the shared `StewardAuthorization`
via `InMemoryStewardAuthorizationStore` exactly as the real `co-sign`
endpoint (`StewardResource.java`) would; newly-vetoed stewards are appended
the same way for `veto`.

After each tick's actions are applied, both arms are evaluated against the
same, single, evolving `StewardAuthorization`:

- **Arm A** (real): `StewardQuorumFunction.evaluate(Operator.GEQ, Q, permission, context)`.
  If true, record `ticksToQuorum = currentTick` and stop tracking Arm A for
  this run (Arm B keeps running independently on the same timeline).
- **Arm B** (hypothetical): if any veto has occurred by this tick, Arm B is
  permanently `VETOED` as of the tick the first veto landed (it can never
  recover, since a hypothetical veto blocks quorum forever, matching the
  ordinary reading of "veto" as a permanent objection) — record
  `vetoedAtTick` once, the first time this becomes true. Otherwise, run
  `HypotheticalVetoBlocksQuorumFunction`; if true, record
  `ticksToQuorum = currentTick` for Arm B.

A run ends when both arms have a terminal outcome (`REACHED`, `VETOED` for
Arm B only, or `STARVED` if `maxTicks` elapses with neither), or at
`maxTicks = 200` ticks, whichever comes first. 200 is generous: with the
smallest swept `p_act` (0.1) and largest pool (`N = 20`), the expected
number of ticks for every steward to have acted at least once (a strict
upper bound on time-to-quorum for any `Q <= N`) is the coupon-collector
expectation `H_N / p_act` ticks, where `H_N = 1 + 1/2 + ... + 1/N` is the
Nth harmonic number (`H_20 ≈ 3.6`, so `H_20 / 0.1 ≈ 36` ticks) —
`maxTicks = 200` leaves wide margin so `STARVED` mostly reflects genuinely
low-probability outcomes, not an undersized horizon.

### 3.3 Sweep dimensions

- `N` (steward pool size): 5, 10, 20.
- `quorum_fraction`: 0.3, 0.5, 0.7 — converted to an absolute
  `Q = max(1, round(N * quorum_fraction))` per point, since production's
  quorum threshold is itself always a fixed absolute count (there is no
  "total steward roster" concept anywhere in the codebase) — this sweep
  expresses that absolute count as a fraction of the simulated pool purely
  to keep the swept grid meaningful across different `N`.
- `p_act`: 0.1, 0.3, 0.5, 0.7, 0.9.
- `veto_share`: 0.0, 0.1, 0.3 (0.0 means Arm B degenerates to exactly Arm
  A's behavior on this timeline, a useful sanity-check slice of the grid).

3 x 3 x 5 x 3 = 135 sweep points. Each point runs `RUNS_PER_POINT = 200`
independent seeded repetitions (the per-tick Bernoulli draws are
stochastic, so a single run per point would not characterize the
distribution — 200 gives a stable median/IQR and a reasonably precise
rate estimate).

Seeding: one base seed for the whole sweep. Each individual run's
`java.util.Random` is constructed from
`mix64(baseSeed * 1_000_003L + pointIndex * 1_000L + runIndex)`, using the
same MurmurHash3-style 64-bit avalanche finalizer E4's final review
introduced (`mix64`, wrapping the final combined long before constructing
`Random`) — applied here from the start, rather than repeating E4's
adjacent-seed-correlation mistake and fixing it after the fact.

### 3.4 Statistics and output

Per sweep point, pooled across its 200 runs:

- `arm_a_reached_rate`: fraction of runs where Arm A reached quorum within
  `maxTicks`.
- `arm_a_median_ticks_to_quorum`, `arm_a_iqr_ticks_to_quorum`: median and
  IQR of `ticksToQuorum` over only the runs where Arm A reached quorum
  (an unreached run has no tick value to include, the same
  "surviving-only" convention E4's final review established for its own
  median). Computed via a `Statistics` class duplicated verbatim into
  `experiments/harness/g1/src/main/java/org/benefits/experiments/g1/Statistics.java`
  (median/iqr/percentile over a `List<Double>`, no other dependencies) —
  `experiments:harness:g1` does not depend on `experiments:harness:e4`,
  since G1 has no other reason to import privacy-pipeline code, so
  duplicating this one small, self-contained class is simpler than adding
  a cross-dependency purely to reuse it. G2 has its own, better reason to
  depend on E4 already (population generation, section 4.2), so this same
  duplication question does not arise there — G2 imports E4's
  `Statistics` directly.
- `arm_a_starved_rate`: `1 - arm_a_reached_rate`, reported explicitly
  rather than left for a reader to derive (matching E4's `data_points`
  convention of always writing out a value a reader would otherwise have
  to recompute).
- `arm_b_reached_rate`, `arm_b_vetoed_rate`, `arm_b_starved_rate` (all
  three sum to 1.0): the three mutually exclusive Arm B outcomes.
- `arm_b_median_ticks_to_quorum`, `arm_b_iqr_ticks_to_quorum`: same
  surviving-only convention, over only Arm B's `REACHED` runs.

CSV `experiments/results/g1/availability.csv` columns: `seed`, `N`,
`quorum_fraction`, `quorum_count`, `p_act`, `veto_share`, `runs`,
`arm_a_reached_rate`, `arm_a_starved_rate`,
`arm_a_median_ticks_to_quorum`, `arm_a_iqr_ticks_to_quorum`,
`arm_b_reached_rate`, `arm_b_vetoed_rate`, `arm_b_starved_rate`,
`arm_b_median_ticks_to_quorum`, `arm_b_iqr_ticks_to_quorum`. The four
`*_median_ticks_to_quorum`/`*_iqr_ticks_to_quorum` columns are written as
the empty string when their arm's `reached_rate` is 0.0 for that point (no
surviving runs to compute a median over), matching E4's established
null-as-empty-string convention.

## 4. G2: consent heterogeneity versus aggregate utility

### 4.1 Production consent filter

Today, consent gates only whether a `ContributionRecord` document can be
*written* to CouchDB (`packages/consent-couchdb`'s `validate_doc_update`
design doc, built from `packages/consent-core/src/evaluate.ts`). Nothing
gates whether an already-written contribution is *included* in a query
result: `Contribution` (the record `PrivacyPipeline`/`KAnonymity` actually
operate on) carries no consent information at all, and
`CouchDbContributionSource.buildSelector` filters only on
`{"type": "contribution", "siteId": siteId}`. This is a real, confirmed
gap, not an assumption. This sub-project closes it, as a real production
feature, wired into `connector/extensions/benefits-privacy`.

**New type**, `connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/ConsentGrant.java`:

```java
package org.benefits.policy;

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

ISO-8601 string timestamps (`expiresAt`/`revokedAt` nullable), compared
lexically, matching `ConsentEvaluator.hasGrant`'s existing, already-proven
convention exactly (`benefits-policy`'s `ConsentEvaluator` is not called
by this filter — the field/purpose/recipientClass/window matching rule is
ported into the new filter directly, since `ConsentEvaluator` operates on
raw `JsonNode` per-record documents, not typed `Contribution`/`ConsentGrant`
objects — but the *rule* is identical: a grant matches when `field`,
`purpose`, and `recipientClass` all equal, `grantedAt <= now`, `expiresAt`
is null or `> now`, and `revokedAt` is null or `> now`).

**`Contribution`** gains one new field, added as an additive change with a
compact secondary constructor so every existing two-arg call site
(`SeededPopulationGenerator` in E4, every existing test, both
`ContributionSource` implementations) keeps compiling unchanged:

```java
public record Contribution(String participantRef, String siteId, Map<String, String> attributes,
                            List<ConsentGrant> consentGrants) {

    public Contribution {
        attributes = Map.copyOf(attributes);
        consentGrants = consentGrants == null ? null : List.copyOf(consentGrants);
    }

    public Contribution(String participantRef, String siteId, Map<String, String> attributes) {
        this(participantRef, siteId, attributes, null);
    }

    // existing ABSENT sentinel and attribute(String) method, unchanged
}
```

`consentGrants == null` means "no consent data tracked for this
contribution" (every existing caller's exact current meaning) and is never
filtered against. `consentGrants` non-null (even if empty) means "consent
is tracked for this participant" and is filtered against whenever the
query declares a purpose/recipientClass (below) — an empty non-null list
means the participant has no matching grants for anything, so they are
excluded from any purpose/recipientClass-scoped query.

**`QuerySpec`** gains two new nullable `String` fields the same way:

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

Every existing call site (`ReleaseRequestResource` in production,
`KAnonymityTest`, `PrivacyPipelineTest`, E4's `E4Queries`,
`ObservedCountsTest`, `LiveSpikeTest`) uses the two-arg constructor and is
unaffected. `purpose`/`recipientClass` are plain strings, not a new enum,
matching the existing string-claims convention already used by
`PurposeFunction`/`RecipientClassFunction` (`context.participantAgent().getClaims().get("purpose")`).

**`PrivacyPipeline.compute`** gains the actual filter, applied only when
both `query.purpose()` and `query.recipientClass()` are non-null (every
existing caller passes null for both, so this branch is unreachable for
E1/E3/E4/`ReleaseRequestResource` and their behavior is byte-identical to
today):

```java
var raw = contributions.contributionsFor(query.siteId());
var filtered = (query.purpose() == null || query.recipientClass() == null)
        ? raw
        : raw.stream().filter(c -> consentCovers(c, query)).toList();
var groups = KAnonymity.aggregate(filtered, query, k);
```

where `consentCovers(Contribution c, QuerySpec query)` returns `true` when
`c.consentGrants() == null` (untracked, unrestricted — preserves existing
behavior for any contribution without consent data even under a
purpose-scoped query) **or** every field name in `query.groupBy()` has a
matching, currently-valid grant in `c.consentGrants()` for
`query.purpose()`/`query.recipientClass()`, using real wall-clock
`Instant.now()` for the "currently valid" window check (no `now` parameter
is added to `compute`, keeping its signature change to zero — this mirrors
how `ConsentEvaluator`'s own design doc frames consent enforcement as
evaluated against live time, not a fixed simulated clock; the deterministic
experiment in section 4.2 sidesteps any real-time dependency entirely by
constructing every grant's `grantedAt`/`expiresAt`/`revokedAt` far enough
in the past/future that "now" never has to be controlled precisely).

**`CouchDbContributionSource`** reads the real `consent.grants` array
already present on every production `ContributionRecord` document (per
`packages/consent-core/src/types.ts`'s `ConsentGrant`, confirmed already
being written by the real write path but never read back) into the new
`Contribution.consentGrants` field, so this filter takes effect for real
CouchDB-backed queries too, not only in-memory ones — the two
`ContributionSource` implementations stay symmetric, matching this
project's own established `ContributionSource` interface contract.

### 4.2 Heterogeneity population generator

New class, `experiments/harness/g2/src/main/java/org/benefits/experiments/g2/HeterogeneousPopulationGenerator.java`.
Reuses `org.benefits.experiments.e4.SeededPopulationGenerator` directly
(imported from `experiments:harness:e4`, unmodified) for the population's
`serviceType`/`channel`/`urgencyLevel` attributes — this generator's own
job is only to layer a `List<ConsentGrant>` onto each resulting
`Contribution`, since `SeededPopulationGenerator.generate` does not
produce consent data (E4 has no reason to, and section 4.1 keeps that
correct: an E4-generated `Contribution` has `consentGrants == null`,
unrestricted, exactly matching E4's own existing, already-committed,
still-correct results).

Fixed for the whole G2 experiment: `purpose = "community-insight"`,
`recipientClass = "community"` (a legal value of the TS
`RecipientClass` union `'community' | 'provider' | 'statistics'`).

For each generated participant, independently, seeded via the same
`mix64`-derived per-participant seed pattern as `SeededPopulationGenerator`
itself: with probability `1 - h` (`h` = the heterogeneity parameter, swept
per section 4.3), the participant gets a **broad grant set**: one
`ConsentGrant` per attribute in `{"serviceType", "channel",
"urgencyLevel"}`, each covering `("community-insight", "community")`,
`grantedAt = "2020-01-01T00:00:00Z"`, `expiresAt = null`, `revokedAt =
null` (always valid). With probability `h`, the participant gets a
**narrow grant set**: the same three grants, but with one attribute
(chosen uniformly at random, seeded) omitted entirely — that participant
has no valid grant for that one field, so any query grouping by that field
excludes them via section 4.1's filter, while a query that does not need
that field still includes them.

`h = 0` therefore reproduces "every participant consents to everything
this workload asks," the implicit assumption every prior experiment
(E1/E3/E4) already made without stating it. `h = 1` means every single
participant is missing coverage for exactly one (randomly chosen) field.

### 4.3 Sweep dimensions

Reuses `org.benefits.experiments.e4.E4Queries.workload()` directly
(imported from `experiments:harness:e4`, unmodified), rebuilding each
`E4Query`'s `QuerySpec` with `purpose`/`recipientClass` attached (a new
`QuerySpec` constructed from the existing one's `siteId()`/`groupBy()`
plus the fixed purpose/recipientClass from section 4.2 — `E4Queries`
itself is not modified). Reuses `Statistics` the same way (imported from
`experiments:harness:e4`, not duplicated — unlike G1, which has no other
reason to depend on E4 and duplicates `Statistics` instead, section 3.4).

- `epsilon`: 0.5, 1.0, 2.0 (three representative values — G2's headline
  swept variable is heterogeneity, not the epsilon-vs-refusal transition
  E4 already characterized in detail, so a coarse epsilon grid is
  sufficient here).
- `population`: 100, 300.
- `k`: 5 (fixed — k-anonymity threshold is not this experiment's focus).
- `skew`: uniform only (fixed — likewise not this experiment's focus;
  limiting these three dimensions keeps the grid a manageable size while
  reserving resolution for `h`).
- `h` (heterogeneity): 0.0, 0.1, 0.2, 0.3, 0.5, 0.7, 1.0 (seven values,
  finely spaced near the low end where a paper reader most wants to see
  the initial slope of the utility-vs-heterogeneity curve).

3 x 2 x 1 x 1 x 7 = 42 sweep points, no repeated trials per point
(deterministic given the seed, exactly like E4 and unlike G1 — a single
population generation and query workload run per point is enough, since
there is no per-tick stochastic process here the way G1 has).

### 4.4 Per-point execution and statistics

One shared `InMemoryBudgetAccountant(TOTAL_SITE_BUDGET = 2.0)` and one
`PrivacyPipeline` per sweep point, exactly mirroring E4's own
`SweepPointRunner` structure (`TOTAL_SITE_BUDGET` reused as the same
constant value, for direct comparability with E4's own results). For each
of the 8 workload queries, in the fixed order: compute **two** ground
truths from `KAnonymity.aggregate(..., k=1)` — one over the full generated
population (`fullTruth`), one over only the subset with a valid grant for
that query's `groupBy` fields (`consentingTruth`, computed the same way
section 4.1's filter would select) — then run the real, consent-aware
`PrivacyPipeline.compute(...)` (with `purpose`/`recipientClass` set, so
section 4.1's filter is genuinely exercised) to get the observed, noised
result. This dual-ground-truth treatment applies identically to all
eight queries, including the two proportion queries (`E4Queries.Kind.PROPORTION_LARGEST`/
`PROPORTION_TARGET`): a proportion query's "ground truth" is itself
computed twice the same way E4 already computes it once (target group's
`KAnonymity.aggregate(..., k=1)` count divided by the total across all
`serviceType` groups) — once from `fullTruth`'s groups, once from
`consentingTruth`'s groups — producing `fullProportion` and
`consentingProportion` exactly the way the two count-based ground truths
above produce `fullTruth`/`consentingTruth`, then compared against the
same single observed proportion the same way section 4.4's two relative-
error statistics compare any grouped query's counts.

Two relative-error statistics per data point, both using the same
suppression/refusal-sentinel convention E4 established (relative error
1.0 for a suppressed group or a refused query):

- **`coverage_relative_error`**: observed vs. `consentingTruth` — isolates
  the privacy mechanism's own noise/suppression error, holding the
  consenting population itself as the reference, the same lens E4 already
  used (just now over a filtered subset instead of the whole population).
- **`exclusion_relative_error`**: observed vs. `fullTruth` — the more novel
  measurement this experiment exists to produce: how far the community's
  reported answer drifts from the true full-community value once
  heterogeneous consent has silently dropped part of the population,
  *before* privacy noise is even considered. At `h = 0` these two
  statistics are identical (nobody excluded, `consentingTruth ==
  fullTruth` for every group), so `h = 0`'s row is a direct sanity check.

Both are pooled to a median/IQR across the whole 8-query workload,
exactly as E4 pools its single relative-error statistic — no
surviving-only split here (that was E4's own fix for its own degenerate
all-inclusive median at high epsilon; G2's epsilon grid, section 4.3, is
coarse and does not aim to characterize that same refusal transition, so
this spec does not add a matching surviving-only pair here — if the real
production sweep turns out to need one, the same final-review-fix-wave
pattern that fixed E4 applies).

Also reported: `excluded_fraction`, the fraction of the generated
population that had no valid grant for at least one of the 8 queries'
`groupBy` fields — the direct, query-independent measurement of "how much
of the community did heterogeneity actually exclude" at that `h`.

CSV `experiments/results/g2/heterogeneity.csv` columns: `seed`, `epsilon`,
`population`, `k`, `h`, `excluded_fraction`,
`coverage_median_relative_error`, `coverage_iqr_relative_error`,
`exclusion_median_relative_error`, `exclusion_iqr_relative_error`,
`suppression_refusal_rate`, `data_points`.

## 5. Non-goals

- No change to `StewardQuorumFunction`, `InMemoryStewardAuthorizationStore`,
  or any other existing `benefits-policy` production class (G1 drives them
  as-is).
- No wiring of `StewardQuorumFunction`/vetoes into a real HTTP-level
  "deadlock" endpoint or any new production governance state machine — G1
  is an offline simulation, not a new feature.
- G1's Arm B (`HypotheticalVetoBlocksQuorumFunction`) is never presented
  as real production behavior anywhere outside its own, explicitly-labeled
  harness code and this spec.
- No change to `KAnonymity`, `LaplaceNoiseSource`, `InMemoryBudgetAccountant`,
  `ReleaseArtifact`, or `BudgetAccountant` — G2 touches only
  `Contribution`, `QuerySpec`, `PrivacyPipeline`, and adds `ConsentGrant`
  and the `CouchDbContributionSource` read path.
- No change to `packages/consent-core`, `packages/consent-odrl`, or
  `packages/consent-couchdb` — G2 reads the TypeScript-side `ConsentGrant`
  shape as a reference for the new Java type's fields, but does not modify
  or call into any TypeScript code.
- No change to E1, E3, or E4's own already-committed code — G2 depends on
  `experiments:harness:e4` read-only (imports `SeededPopulationGenerator`,
  `E4Queries`, `Statistics`); it does not modify that module.
- No revocation/re-signing simulation in G1 (a steward acts at most once
  per run, section 3.2) and no per-field consent partial-coverage nuance
  in G2 beyond "one omitted field" (section 4.2) — both are deliberate
  simplifications to keep each sweep's grid a tractable size, not
  oversights.
- Figure generation gets its own spec, as with E1/E3/E4.

## 6. Global constraints carried from the parent project

- No em-dashes in code, docs, or commits.
- No LLM/AI-tooling attribution in commits.
- No emojis in code or logs.
- Seeded determinism throughout (`java.util.Random(seed)`, mixed via the
  MurmurHash3-style `mix64` finalizer from the very first implementation
  in both G1 and G2, not retrofitted after the fact), matching the
  "results reproduce from seed" gate this sub-project shares with
  E1/E3/E4.
- Verify behavior against the real production classes directly
  (`StewardQuorumFunction`, `InMemoryStewardAuthorizationStore` for G1;
  `PrivacyPipeline`, `KAnonymity`, `CouchDbContributionSource` for G2)
  rather than assuming their behavior from reading source alone — a live
  spike (mirroring E4's Task 2 pattern: construct the real classes with a
  handful of hand-built values and assert on real output) is required
  before either full sweep is built, for both G1 and G2 separately, since
  neither has an existing live-verification spike to lean on.
