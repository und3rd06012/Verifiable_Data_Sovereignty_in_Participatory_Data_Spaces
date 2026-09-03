# E4: privacy budget versus utility

## 1. Purpose and scope

This is the second phase-7 sub-project (after E1 + E3-full), per
`docs/superpowers/specs/2026-08-30-benefits-reference-implementation-design.md`'s
phasing table (section 8: phase 7 = "E1, E4, G1, G2, figure
generation") and section 5's own E4 description: "Sweep epsilon 0.1 to
2.0, site population 30 to 300, k from 3 to 10, over queries a
community would actually ask: counts, proportions, cross tabs by
service type. Answers the conference paper's own open challenge on
privacy budget and community data utility. Expected to be the
article's most useful figure."

Unlike E1 and E3, this experiment touches no live infrastructure at
all: no CouchDB, no Docker. `connector/extensions/benefits-privacy`'s
`PrivacyPipeline`, `KAnonymity`, `LaplaceNoiseSource`,
`InMemoryBudgetAccountant`, and `InMemoryContributionSource` are
already real, dependency-free, in-memory production classes with no
database behind them. E4 drives them directly with synthetic data; it
does not touch, extend, or modify any of them.

G1, G2, and figure generation remain out of scope, each with its own
spec.

## 2. Repository layout

```
experiments/harness/e4/    new Gradle module, :experiments:harness:e4
  src/main/java/org/benefits/experiments/e4/
  src/test/java/org/benefits/experiments/e4/
experiments/results/e4/    seeded CSV output (gitignored, per the
                            existing experiments/results/**/*.csv
                            pattern; committed deliberately once real
                            production output exists, matching E1/E3)
```

Registered in `settings.gradle.kts` alongside `:experiments:harness:e3`.
`build.gradle.kts` depends on `project(":connector:extensions:benefits-privacy")`,
the same pattern E3 already uses for `benefits-audit`; this pulls in
that module's own EDC and Jackson dependencies transitively, unused by
E4 but unavoidable when depending on the module as a whole; already an
accepted precedent, not a new one.

## 3. Synthetic population generator

A new class, `SeededPopulationGenerator`, produces a `List<Contribution>`
for one synthetic site, deterministically from a seed
(`java.util.Random(seed)`, matching `SeededFixtureGenerator`'s
established convention from the E1/E3 sub-project, never
`Math.random()`/`ThreadLocalRandom`).

Each synthetic `Contribution` carries three quasi-identifier attributes
in its `attributes` map:

- `serviceType`: `housing`, `food-assistance`, `healthcare`,
  `legal-aid`, `employment` (5 categories).
- `channel`: `app`, `sms`, `paper`, `voice`, the same four values
  `ContributionRecord`'s `channel` field already uses elsewhere in this
  codebase, reused rather than inventing a new vocabulary.
- `urgencyLevel`: `routine`, `urgent`, `emergency` (3 categories).

Two distribution modes, both swept (section 5):

- **Uniform**: each attribute's categories are equally likely,
  independently assigned per contribution.
- **Skewed**: `channel` is skewed toward `paper` at 61%, the remaining
  39% split evenly across `app`/`sms`/`voice`. This is the one
  parent-spec workshop statistic ("61% paper channel preference,"
  section 5's Validity subsection) that maps directly onto an attribute
  this sweep uses, so it is used verbatim rather than approximated.
  `serviceType` and `urgencyLevel` have no corresponding workshop data,
  so their skew is a declared assumption (one dominant category each:
  `housing` at 50% and the remaining four service types splitting the
  other 50% evenly; `routine` at 60%, `urgent` and `emergency` splitting
  the remaining 40% evenly), stated here as an assumption per the
  parent spec's own rule ("parameterised from the workshop data where
  it exists ... and declared as assumptions where it does not"). The
  other two workshop statistics (legal status fear, institutional
  distrust) do not correspond to any attribute this sweep queries and
  are not used here.

`participantRef` is synthesized as `synthetic-participant-<seed>-<index>`,
one per generated contribution (no repeat participants within one
generated population. A real repeat-contribution model is not needed
for this sweep, since `KAnonymity` counts distinct participants and a
population generator that never repeats a `participantRef` already
gives every group's true count an unambiguous value to compare noised
output against).

## 4. The eight queries

All eight run as `QuerySpec` instances against `KAnonymity`/`PrivacyPipeline`
directly, no new production code:

**Counts** (one per attribute, deliberately different category
cardinalities: 5, 4, 3):
1. `groupBy=["serviceType"]`
2. `groupBy=["channel"]`
3. `groupBy=["urgencyLevel"]`

**Proportions** (a common category and a rare one, since a rare
category's noise-to-signal ratio is structurally worse):
4. Largest `serviceType` group's share of the total `serviceType`
   count (built from query 1's own grouping, not a separate `QuerySpec`).
5. `legal-aid`'s share of the total `serviceType` count (same
   grouping as query 1 and 4; `legal-aid` is fixed as "the rare one"
   rather than picked dynamically per point, so results are consistent
   across sweep points rather than tracking whichever category happens
   to be smallest at each one).

**Cross-tabs** (increasingly fine-grained, so suppression should bite
progressively harder):
6. `groupBy=["serviceType", "channel"]`
7. `groupBy=["serviceType", "urgencyLevel"]`
8. `groupBy=["serviceType", "channel", "urgencyLevel"]`

## 5. Sweep dimensions

The parent spec gives ranges, not points; this sub-project fixes
concrete values:

- **epsilon** (per-query privacy parameter, see section 6 for how this
  interacts with the shared site budget): 0.1, 0.5, 1.0, 1.5, 2.0.
- **site population**: 30, 100, 200, 300.
- **k**: 3, 5, 7, 10.
- **population skew**: uniform, skewed (section 3).

5 x 4 x 4 x 2 = 160 sweep points. No repeated trials: unlike E1, this
is a pure, fast, fully deterministic computation from one seed with no
wall-clock noise to average out. The median/IQR the parent spec asks
for is computed across the query workload itself (section 6), not
across repeated runs of the same point.

## 6. Per-point execution: a shared-budget query workload

Each sweep point generates one seeded population (section 3, sized and
skewed per that point), then runs all eight queries (section 4) in the
fixed order listed above against **one shared `InMemoryBudgetAccountant`**
for that site, not a fresh accountant per query. This matters: the
parent spec frames E4 as "queries a community would actually ask,"
plural, and `BudgetAccountant.reserve()` is already atomic and shared
per site in the real production code specifically so that one query
cannot spend budget another query is relying on. A workload that gave
every query its own fresh budget would never exercise that property at
all, even though it is one of this pipeline's real, tested design
decisions.

**Total site budget is a fixed constant, 2.0, not swept.** The swept
"epsilon" is the amount each individual query requests
(`PrivacyPipeline.compute(..., epsilon)`), matching the parent spec's
literal framing (epsilon is the per-query Laplace noise-scale
parameter: `LaplaceNoiseSource` scales noise at `1/epsilon`, so a
larger per-query epsilon means less noise on that query specifically).
With a fixed total of 2.0 and eight queries each requesting the swept
epsilon in sequence, the number of queries that actually get answered
before the site's budget is exhausted is `min(8, floor(2.0 / epsilon))`:

| swept epsilon | queries answered before refusal |
|---|---|
| 0.1 | 8 (all) |
| 0.5 | 4 |
| 1.0 | 2 |
| 1.5 | 1 |
| 2.0 | 1 |

This was chosen deliberately, not picked arbitrarily: it produces a
genuine, non-degenerate transition across the whole swept epsilon
range, and gives the sweep its real headline shape. At low epsilon,
every query gets answered but each is heavily noised (poor utility
from *noise*). At high epsilon, individual answered queries are far
less noised, but most of the workload gets refused outright once
budget runs out (poor utility from *refusal*). The two failure modes
pull utility down from opposite ends of the epsilon range, which is
exactly the tradeoff this experiment exists to show. A total budget
large enough that all eight queries always succeed (e.g. `8 x 2.0`)
would never exercise refusal at all; a total small enough that no
epsilon value ever succeeds would show nothing but refusal. 2.0 sits
between those extremes across the whole swept range.

Refused queries are queries 5 through 8 (in the fixed order above)
once budget is exhausted at a given point: for example, at
epsilon=0.5, queries 1-4 succeed and queries 5-8 are refused outright
(`PrivacyPipeline.compute` returns `Optional.empty()`, changing
nothing in the accountant per its own contract).

## 7. Utility: relative error per group

For every query in the workload, "ground truth" is computed with the
same real `KAnonymity.aggregate(contributions, query, k=1)` call the
production pipeline itself uses internally, with `k=1` (which never
suppresses anything, since every surviving group already has at least
one distinct participant), which reuses exactly the pipeline's own
grouping and counting logic rather than reimplementing it, so ground
truth and observed output are guaranteed to use identical group keys.

For each of the three count queries and three cross-tab queries: every
group in the ground truth becomes one relative-error data point.

- If the query succeeded (budget was available) and that specific
  group survived suppression in the observed `ReleaseArtifact`:
  relative error = `abs(noised - true) / true` (a group with a true
  count of zero cannot occur, since `KAnonymity.aggregate` never
  returns an empty group). This value is not capped at 1.0: Laplace
  noise has unbounded support, so a small true count can genuinely
  receive a noise draw larger than itself and read as more than 100%
  error. That is real, meaningful signal about how badly noise hurts a
  small group specifically, not a defect to clamp away. The 1.0 cap
  below applies only to the suppressed and refused cases, where there
  is no noised value to compare against at all.
- If the query succeeded but that group was suppressed (present in
  ground truth, absent from the observed release because it fell below
  k): relative error = 1.0 (capped, not infinite. The community gets
  no answer for that cell at all, a complete information loss for that
  group, which this convention treats as the worst possible score
  rather than an unbounded one).
- If the query itself was refused for budget reasons (section 6):
  every group that query's ground truth would have produced counts as
  relative error = 1.0, for the same reason.

For the two proportion queries (4 and 5): each contributes exactly one
data point, not one per group, since a proportion is itself a single
number. True proportion is that category's true count divided by the
true total across all `serviceType` groups (unsuppressed, from the
same `k=1` ground truth as query 1). Observed proportion, when query 1
succeeded, uses only the groups that survived suppression: the target
category's noised count (0 if that specific group was itself
suppressed) divided by the sum of every surviving group's noised
count. If query 1 itself was refused for budget reasons, or the
observed denominator is 0 (every group suppressed), relative error is
1.0 for the same reason as above.

**One point's utility** is the median and interquartile range of every
relative-error data point produced by that point's whole eight-query
workload (three counts' groups, two proportions, three cross-tabs'
groups, pooled together), not per query, matching the design already
approved: "the median and IQR of all these per-group relative errors
across the whole 8-query workload."

**Suppression/refusal rate**, reported alongside utility as a second
statistic: the fraction of that same pooled data-point set whose
relative error is exactly 1.0 for either reason (suppressed group or
refused query), out of the total pooled count. This surfaces "how much
was lost" separately from "how bad was what remained," which the
capped relative-error alone partially obscures.

## 8. Output

One CSV, `experiments/results/e4/utility.csv`:

`seed`, `epsilon`, `population`, `k`, `skew`, `median_relative_error`,
`iqr_relative_error`, `suppression_refusal_rate`, `data_points`.

`data_points` is the total count of pooled relative-error values that
median/IQR/suppression-refusal-rate were computed over for that row
(varies with population, k, and skew, since group counts and
suppression both depend on those), recorded so a reader can judge how
much a given row's median is actually resting on.

## 9. Non-goals

- No change to `connector/extensions/benefits-privacy`'s production
  classes. `PrivacyPipeline`, `KAnonymity`, `LaplaceNoiseSource`,
  `InMemoryBudgetAccountant`, `InMemoryContributionSource`, `QuerySpec`,
  `Contribution`, `GroupedCount`, `ReleaseArtifact` are all consumed
  exactly as they exist today.
- No sums or means: `QuerySpec` deliberately supports only grouped
  counts (its own javadoc: "their sensitivity depends on the value
  range, and an incorrect range silently under-noises the result"),
  and this sub-project does not route around that deliberate production
  constraint.
- No CouchDB-backed `ContributionSource`/`BudgetAccountant` variants:
  `InMemoryContributionSource`/`InMemoryBudgetAccountant` are used
  throughout, since this experiment measures the privacy mechanism's
  own math, not a storage layer already covered by phase 3's own tests.
- Figure generation, G1, G2: each gets its own spec.

## 10. Global constraints carried from the parent project

- No em-dashes in code, docs, or commits.
- No LLM/AI-tooling attribution in commits.
- No emojis in code or logs.
- Seeded determinism throughout (`java.util.Random(seed)`, never
  `Math.random()`/`ThreadLocalRandom`), matching the "results reproduce
  from seed" gate this sub-project shares with E1/E3.
- Verify behavior against the real production classes directly
  (`PrivacyPipeline`, `KAnonymity`, `LaplaceNoiseSource`,
  `InMemoryBudgetAccountant`) rather than assuming their behavior from
  reading source alone. This project's live-verification discipline
  applies here as a live *code* check (a small spike task exercising
  the real classes with a handful of synthetic contributions before
  building the full sweep), even though there is no live infrastructure
  to verify against the way E1 had CouchDB.
