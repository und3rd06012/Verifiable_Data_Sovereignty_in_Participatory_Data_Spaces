# BENEFITS Privacy Pipeline (Phase 3): Design

Date: 2026-08-31
Status: pending review
Parent spec: docs/superpowers/specs/2026-08-30-benefits-reference-implementation-design.md
Builds on: phases 0 to 2, complete on branch phases-0-2

## 1. Purpose

Phase 3 adds the anonymisation stage of the release path: k-anonymity, Laplace
differential privacy, and a per-site privacy budget accountant, enforced inside
the Eclipse Dataspace Components connector.

It exists before the interfaces (phase 6) for a concrete reason. A steward
reviewing a release request is asked by the governance rubric to judge
re-identification risk in small subgroups and the proportionality of the
requested aggregation. Neither judgement is possible unless the anonymised
artifact, its computed k, and the remaining budget already exist. A steward
portal built before this phase would be asking a human to co-sign something
they cannot see.

Parameters are fixed by the parent spec: k=5, epsilon 0.5 per query, and
epsilon_total 2.0 per site.

## 2. Placement

The pipeline is an EDC data plane extension. A `DataSourceFactory` is registered
with the `PipelineService` for a BENEFITS-specific DataAddress type, so the only
data source that can serve this asset type is one that serves anonymised
artifacts. Raw contributions physically cannot leave through the release path.

Two alternatives were considered and rejected: a pre-release service the steward
gate calls, and a plain library invoked at release-request creation. Both are
easier to build, and both make anonymisation a step in a pipeline rather than an
enforced boundary, which is the distinction the parent spec's thesis rests on.

### 2.1 Compute once, store, serve stored bytes

The artifact is computed ONCE when a release request is created, stored, and
served byte-identically on every transfer. The DataSource does not compute.

This is not an optimisation. Four independent reasons require it:

**The averaging attack.** If noise were redrawn per transfer, a requester could
re-run the same transfer N times, average the results, and recover the true
value with error falling as the square root of N, while the budget accountant
charged epsilon exactly once. Serving stored bytes makes repeat transfers
return identical output and consume no further budget.

**The steward signature already commits to it.** `StewardAuthorization` carries
a `datasetHash`, and stewards co-sign that exact dataset. A hash computed at
transfer time would be a hash of something that did not exist when it was
signed.

**Negotiation-time enforcement requires it.** The `benefits:kAnonymity` and
`benefits:epsilonBudget` constraint functions evaluate during contract
negotiation, long before any transfer. The values they compare against must
already be stored.

**Steward review requires it**, per section 1.

## 3. What the pipeline computes

### 3.1 Query family

Grouped counts only: a count of contributions, grouped by zero or more
quasi-identifier fields such as site, channel, or service type.

This is deliberate scope, not an omission. Proportions are two counts, and
cross-tabulations are counts grouped by two dimensions, so grouped counts
support the experiments the parent spec plans (E4 sweeps counts, proportions
and cross-tabs by service type). Means and sums are excluded because their
sensitivity depends on the value range, and an incorrect range silently
under-noises the result. That is a correctness hazard not worth introducing for
a query family the experiments do not require.

### 3.2 Sensitivity

L1 sensitivity is 1, conditional on each participant contributing at most one
row to a given query. The pipeline enforces that condition by deduplicating on
`participantRef` before counting. Noise is therefore drawn from
Laplace(0, 1/epsilon) per surviving group.

Stating the condition and enforcing it in code is what makes the calibration
defensible. A sensitivity assumed rather than enforced is a silent
under-noising.

### 3.3 Order of operations, and a limitation that must be disclosed

k-anonymity suppression runs on true counts. Groups whose distinct-participant
count is below k=5 are suppressed entirely. Laplace noise is then added to the
surviving groups.

**The suppression decision is not itself differentially private.** Which groups
appear in the output depends on unnoised counts, so group membership leaks
information that the noise does not protect. Only the released values carry the
formal guarantee.

This is standard practice and a known weakness, and it must be stated plainly in
the manuscript rather than glossed. The parent spec's honesty bar (its section
4.5.1) exists because an artifact that overclaims a formal guarantee is worse
than one that claims less and proves it.

## 4. Budget accountant

Per site, epsilon_total is 2.0. Each release reserves epsilon, 0.5 by default,
and is refused when the remaining budget is insufficient.

### 4.1 The accountant must be atomic

Ruling R30 permitted `StewardAuthorizationStore.save` to replace wholesale under
a documented sole-writer precondition, because losing a steward signature is a
data-integrity problem with one writer in practice.

The accountant cannot take that deal. Two concurrent release requests each
reading a remaining budget of 0.5, each concluding they can afford 0.5, and each
proceeding, spend 1.0 against a budget of 0.5. That is not lost data. It is a
privacy guarantee silently violated, with the released figures under-noised and
nothing in the system aware of it.

The accountant therefore exposes exactly one mutating operation,
**reserve-or-refuse**, which compare-and-decrements atomically and returns either
a reservation or a refusal. It never exposes a read that a caller could follow
with a separate write.

## 5. Constraint functions

Two functions join the existing family, following the pattern
`StewardQuorumFunction` established: read `benefits:releaseRequest` from a
sibling constraint on the same rule, look up the stored artifact, compare, fail
closed with a reported problem on every uncertain path.

- `benefits:kAnonymity gteq "5"` compares the stored artifact's computed minimum
  group size.
- `benefits:epsilonBudget lteq "0.5"` compares the epsilon actually reserved for
  this release.

Both right operands are emitted as strings, per ruling R40: EDC's management API
validator rejects numeric right operands with a ClassCastException.

### 5.1 These land as one change

Ruling R39 recorded that EDC rejects an entire policy at definition-creation
time, with HTTP 400, when a left operand has no registered function. Phase 3
must therefore add, in a single change: the two constraint functions, their
scope bindings, the `kAnonymity` and `epsilon` fields on `CompileOptions`, and
the compiler's emission of both constraints. Splitting them reproduces exactly
the failure that removed them in phase 2.

## 6. Reading contributions

The connector does not talk to CouchDB today. Phase 3 introduces that: a Java
HTTP client, with credentials in configuration under `benefits.couchdb.*` and
never in the repository.

The pipeline reads through a `ContributionSource` interface with one
CouchDB-backed implementation. Tests inject a fixed contribution set directly,
so every k-anonymity and differential privacy test runs without a database, and
a single integration test covers the reader itself. Without that seam the
known-answer tests become slow and flaky for no benefit.

## 7. Gates

**Gate 0, transfer probe.** No data transfer has ever run in this project: the
runtime pulls `controlplane-base-bom` and `iam-mock` only, and the C1 harness
has no transfer step. Before anything else, add the data plane BOM, register a
trivial `DataSourceFactory`, drive one real transfer through the management API,
and observe bytes arriving at the consumer with a recognisable marker. This has
the shape of phase 0's constraint-function probe.

If this gate fails or costs disproportionately, the recorded fallback is the
pre-release service rejected in section 2, and that decision gets written down
rather than worked around.

**Gate 1, mechanism.** Known-answer tests for suppression and for Laplace noise.
Requires an injectable RNG, seeded in tests and secure in production. A
differential privacy implementation that cannot be tested deterministically
cannot be verified at all.

**Gate 2, accountant.** N threads race for a budget admitting exactly M
reservations, and exactly M succeed.

**Gate 3, enforcement.** Both constraint functions registered, bound, emitted
and evaluated, with the C1 suite's two privacy cases refusing at negotiation:
k below threshold, and budget exceeded.

**Gate 4, end to end.** An anonymised artifact released through the data plane,
and the same transfer run twice returning byte-identical output. Identical bytes
prove the noise was drawn once and stored rather than redrawn per transfer,
which is section 2.1's claim under test.

## 8. Out of scope

Floating-point side channels in the Laplace mechanism, per the parent spec.
Means and sums, per section 3.1. The audit chain (phase 4), the simulated device
fleet (phase 5), and the interfaces (phase 6).

## 9. Open questions

1. Whether `DspDataAddress` exposes arbitrary properties, so the release request
   id can travel on the asset's DataAddress and be read by the DataSource.
   `DataFlowStartMessage.getDataAddress()` is confirmed to exist; the property
   accessor is not yet confirmed. Settled by gate 0.
2. Whether the data plane requires `data-plane-self-registration` in this
   single-runtime topology, or whether the control plane finds the embedded data
   plane without it. Settled by gate 0.
3. What epoch or granularity the budget accountant persists at, and whether
   in-memory storage is acceptable given that a restart would reset privacy
   protection. In-memory matches the steward store's precedent, but the
   consequence of loss is materially worse. Settled before gate 2.
