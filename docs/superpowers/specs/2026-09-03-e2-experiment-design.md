# E2 Experiment: Revocation Under Partition, Design

## 1. Purpose and scope

The fourth phase-7 experiment sub-project (after E1+E3-full, E4, and G1+G2, all
complete with real production data committed). Per the parent spec (section 5):
"Revocation under partition. A participant revokes consent while their device
is offline with records already queued. Measure revocation propagation
latency across connectivity duty cycles matched to the target population, and
confirm the leaked record count is zero. Also covers time to consistency and
conflict rates under the published 'user preference first' resolution
strategy."

This closes a real gap between what was specced and what was built. Phase 5's
own spec (`docs/superpowers/specs/2026-09-01-benefits-device-fleet-design.md`)
explicitly states this formal experiment was meant to run in phase 7 ("with
E1, E3's formal run, and E4, matching the precedent phase 4 set for E3"), but
the parent spec's phase-7 gate row was never actually updated to list it, and
no E2 sub-project was ever built. Phase 5 proved the underlying mechanism
works (its own gate, commit `15cb7a5`: zero leaked records, conflict
resolution converges). E2 is the formal, swept, reportable-figures version of
that same mechanism, exercising the real, unmodified `packages/device-sim`
classes under a parameter sweep, exactly mirroring how E3's formal sweep in
phase 7 built on top of phase 4's already-proven audit-chain mechanism, and
how E4's formal sweep built on top of phase 3's already-proven privacy
pipeline.

## 2. Repository layout

New TypeScript npm workspace, `experiments/harness/e2` (package
`@benefits/experiment-e2`), alongside the existing `experiments/harness/e1`.
Unlike E3/E4/G1/G2, this is not a Java Gradle module: `packages/device-sim`
is TypeScript, so E2 follows E1's own structure and conventions instead
(`vitest` for tests, `tsc` for build, `npm` workspace).

Dependencies: `@benefits/device-sim` (the real `Device`, `DutyCycle`,
`syncDevice`, `resolveConflicts`, `injectConflict` classes, unmodified) and
`@benefits/consent-couchdb` (for `buildDesignDoc()`, to install the real S1
validation design document into the sweep's CouchDB database, matching
`fleet.integration.test.ts`'s own setup exactly). Three small utility files
are duplicated verbatim from `experiments/harness/e1/src/` rather than
depended on cross-experiment: `csv.ts`, `rng.ts` (`mulberry32`), and
`host-info.ts` (`readHostCpuModel`). This mirrors G1's own established
precedent of duplicating `Statistics.java` from E4 rather than adding a
cross-experiment dependency for a few small, self-contained files: E1's own
already-committed code is never modified.

Results: `experiments/results/e2/partition.csv`.

## 3. The fleet simulation model

### 3.1 Device roles, per trial

One trial is one simulated fleet of 10 devices, split into three fixed
roles, mirroring phase 5's own gate test's device-role shape (which already
proved this exact scenario end to end) scaled up slightly for more signal
per trial:

- **3 revocation devices.** Each calls `device.contribute(...)` once (an
  ordinary queued record, `declaredPurpose: 'service-improvement'`), then
  immediately calls `device.revoke('service.satisfaction', 'service-improvement',
  'statistics', nowIso)` while still offline, before any sync has ever
  happened for that device. This is `revoke()`'s own real, proven behavior:
  it stamps the local grant's `revokedAt` and sweeps the just-queued record
  out of the device's own outbox, synchronously, with no online check
  gating it (spec section 3, phase 5's own design). This is the literal
  scenario the parent spec names: "revokes consent while their device is
  offline with records already queued."
- **3 conflict devices.** Each calls `device.localDb().put({_id: <explicit
  id>, ...})` directly, **not** `device.contribute()`, because `contribute()`
  does not return the auto-generated `_id` it assigns internally
  (`contribution-${Date.now()}-${random}`), and `injectConflict(remoteUrl,
  docId, conflictingBody)` needs a known `docId` to write its rival revision
  against. This is exactly the pattern `fleet.integration.test.ts`'s own
  device 1 already uses (`devices[1].localDb().put({_id: conflictDocId, ...})`
  followed by `injectConflict(REMOTE_URL, conflictDocId, ...)`), not an
  invented workaround. Each conflict device then calls `injectConflict(...)`
  immediately (this does not require the document to exist remotely yet:
  `conflictInjector.ts`'s own logic handles `currentGeneration = 0` when the
  remote document does not exist, producing a valid first-generation
  conflict).
- **4 baseline devices.** Each calls `device.contribute(...)` once, no
  revocation, no conflict. These exist so the trial's leaked-record and
  design-doc-validation checks have real, ordinary records that must reach
  CouchDB successfully, the same role phase 5's own gate's devices 2-5 play.

All three roles' tick-0 setup calls (`contribute`/`revoke`/`localDb().put`/
`injectConflict`) happen before the tick loop starts, at simulated tick 0,
representing "already queued, then revoked/conflicted, while offline."

### 3.2 The tick loop

Each device gets its own `DutyCycle(onlineProbability, seededRng)` (the real,
unmodified class; spec section 4 fixes `onlineProbability` as the one swept
dimension, matching that class's own code comment: "phase 7's sweep varies
`onlineProbability`... without touching this class"). For `tick` from 1 to
`MAX_TICKS = 200` (same horizon G1 used earlier this session, for the same
reason: even at the lowest swept `onlineProbability` of 0.05, the geometric
tail probability of a device never coming online within 200 ticks is
`0.95^200 ≈ 3.5e-5`, negligible):

- Call `cycle.tick()` for every device not yet resolved.
- For each device whose `cycle.isOnline()` is now true and which has not yet
  completed its tracked action:
  - **Revocation device**: call `await syncDevice(device, remoteUrl)`.
    Record `propagationTicks = tick` for this device. This is `syncDevice`'s
    real behavior: it pushes whatever remains in the device's outbox (the
    already-swept revocation record is gone, so nothing revocation-related
    is pushed; any record from an earlier failed revoke would be) and marks
    it sent.
  - **Conflict device**: call `await resolveConflicts(device, remoteUrl)`.
    Record `consistencyTicks = tick` for this device.
    `resolveConflicts` does not require a prior `syncDevice` call for this
    specific document (it reads the remote conflict state and the device's
    own local copy directly), matching its real, already-proven behavior.
  - **Baseline device**: call `await syncDevice(device, remoteUrl)`, no
    metric recorded (baseline devices exist to give the checks below real
    synced records, not to produce their own statistic).
- If every device in the trial has completed its tracked action (or has no
  tracked action, i.e. baseline devices only need to have synced once),
  stop the loop early.

If any device has not come online by `MAX_TICKS`, the trial throws rather
than silently dropping a data point: given the probability argument above,
this indicates a real bug (a stuck duty cycle, a hung network call), not a
legitimate rare outcome to average away, the same "not-100%-is-a-bug"
framing E3 already established for this project's tamper-detection
experiment.

### 3.3 Per-trial checks

After every device has completed its tracked action:

- **Leaked record check**: for each of the 3 revocation devices, query the
  shared CouchDB database for any document matching that device's own
  `participantRef` and `declaredPurpose: 'service-improvement'`. Must be
  empty. Any hit increments `leakedRecordCount` and is a real defect, not a
  statistic to report gracefully.
- **Unresolved conflict check**: for each of the 3 conflict devices, fetch
  its own conflict document (`remote.get(docId, {conflicts: true})`) and
  confirm its `_conflicts` array is empty. Any non-empty result increments
  `unresolvedConflictCount`, likewise a real defect.

### 3.4 Database strategy

One shared CouchDB database for the entire sweep, created once with the real
S1 design document installed (`buildDesignDoc()` from `@benefits/consent-couchdb`,
matching `fleet.integration.test.ts`'s own `beforeAll` setup exactly), never
recreated between trials. Every device's `participantRef` is globally unique
across the whole sweep (`p-e2-<seed>-<onlineProbability index>-<trial
index>-<device index>`), so trials never collide with each other and no
per-trial database teardown/recreation overhead is needed. Document volume
across the whole sweep is small (10 devices x 1-2 documents each x 150
trials, well under 3,000 documents total), nowhere near the memory-pressure
territory E1's own per-batch-size-tier database recreation had to work
around at far larger document counts.

## 4. Sweep dimensions

- `onlineProbability`: 0.05, 0.1, 0.3, 0.5, 0.9 (five values, spanning
  "almost always offline" through "almost always online"; no existing
  connectivity/duty-cycle percentage exists anywhere in this repository to
  match against the target population, so this grid is a documented
  assumption, the same footing E4's own population/skew parameters and
  G1's own quorum-fraction grid stood on, not a workshop-sourced figure).
- `trials`: 30 per `onlineProbability` value (150 fleet trials total across
  the whole sweep). Each trial pools 3 revocation-latency and 3
  consistency-latency data points, giving 90 of each statistic's pooled
  data points per sweep point, a comparable order of magnitude to this
  session's other experiments' own pooled sample sizes per point.

5 sweep points, 150 real fleet trials, no further cross-product (unlike
E4/G1/G2's multi-dimensional grids, `onlineProbability` is the only
dimension the parent spec actually asks this experiment to sweep; fleet
composition, `MAX_TICKS`, and the device-role split are fixed structural
choices, not swept parameters).

## 5. Statistics and output

Per sweep point, pooled across its 30 trials:

- `propagation_median_ticks`, `propagation_iqr_ticks`: median and IQR (the
  same linear-interpolation convention E4/G1/G2 already use; ported to
  TypeScript as a small, dependency-free function in this harness, since no
  TypeScript equivalent of the Java `Statistics` class exists anywhere in
  this repository yet) of `propagationTicks` across all 90 revocation-device
  data points at this `onlineProbability` value.
- `consistency_median_ticks`, `consistency_iqr_ticks`: median and IQR of
  `consistencyTicks` across all 90 conflict-device data points.
- `propagation_data_points`, `consistency_data_points`: always 90 given the
  fixed fleet composition and the no-silent-drop rule (section 3.2), written
  explicitly anyway, matching this project's own established convention of
  never leaving a reader to recompute a count.
- `leaked_record_count`, `unresolved_conflict_count`: summed across all 30
  trials at this sweep point. Must be exactly 0 in every row. These are
  correctness gates reported alongside the timing statistics, not
  themselves swept or expected to vary; the parent spec's own phrasing
  ("confirm the leaked record count is zero") frames this as a
  confirmation, not a measurement with a distribution.
- `host_cpu_model`, `throttle_cpus`, `throttle_memory_mb`: descriptive,
  pass-through metadata recorded alongside every row, mirroring E1's own
  `run-e1.ts`/`sweep.ts` pattern exactly (`readHostCpuModel()` plus
  operator-supplied throttle parameters; the code does not itself enforce
  any CPU/memory constraint, matching E1's own precedent of recording,
  not applying, the low-end device profile, per the parent spec's own
  requirement that this throttle be "recorded with the results").

CSV `experiments/results/e2/partition.csv` columns: `seed`,
`online_probability`, `trials`, `propagation_median_ticks`,
`propagation_iqr_ticks`, `propagation_data_points`,
`consistency_median_ticks`, `consistency_iqr_ticks`,
`consistency_data_points`, `leaked_record_count`,
`unresolved_conflict_count`, `host_cpu_model`, `throttle_cpus`,
`throttle_memory_mb`.

## 6. Non-goals

- No change to `packages/device-sim`'s production classes. `Device`,
  `DutyCycle`, `syncDevice`, `resolveConflicts`, `injectConflict` are all
  consumed exactly as they exist today.
- No change to `packages/consent-core` or `packages/consent-couchdb`. E2
  reads `buildDesignDoc()` and the `ConsentGrant`/`ContributionRecord`
  shapes as they already exist; it does not modify either package.
- No change to `experiments/harness/e1`'s own already-committed code. Its
  `csv.ts`/`rng.ts`/`host-info.ts` are duplicated, not imported, so a
  future change to E1's own harness never silently changes E2's behavior
  or vice versa.
- No actual CPU/memory throttle enforcement inside the harness itself
  (section 5): `throttleCpus`/`throttleMemoryMb` are recorded metadata,
  applied externally (Docker/cgroups) if desired when the sweep is
  actually run, matching E1's own precedent exactly.
- No second, independently-written fleet-conflict source. Phase 5's own
  spec already considered and rejected simulating a second device per
  participant as an organic conflict source ("a single writer device has
  nothing to organically conflict with"); E2 inherits that rejection and
  uses `injectConflict` exclusively, the only real mechanism that exists.
- No sweep of fleet size or device-role composition. The parent spec asks
  this experiment to sweep connectivity duty cycles specifically; fleet
  composition is a fixed structural choice (section 3.1), not a second
  swept dimension.
- Figure generation gets its own spec, as with E1/E3/E4/G1/G2.

## 7. Global constraints carried from the parent project

- No em-dashes in code, docs, or commits.
- No LLM/AI-tooling attribution in commits.
- No emojis in code or logs.
- Seeded determinism throughout (`mulberry32` from a fixed seed, matching
  E1's own established TypeScript convention; never `Math.random()` except
  as `DutyCycle`'s own documented, injectable default, which this harness
  always overrides with the seeded generator), matching the "results
  reproduce from seed" gate this sub-project shares with every other
  phase-7 sub-project.
- Verify behavior against the real production classes directly (`Device`,
  `DutyCycle`, `syncDevice`, `resolveConflicts`, `injectConflict`, and the
  real S1 design document via `buildDesignDoc()`) rather than assuming
  their behavior from reading source alone. A live spike (mirroring every
  other phase-7 sub-project's own Task 2 pattern: construct the real
  classes directly against a live CouchDB with a handful of hand-built
  values, assert on real output, no mocks) is required before the full
  sweep is built.
- A live, reachable CouchDB is required (matching E1's own real-CouchDB
  dependency, `experiments/harness/e1`'s test suite already establishes
  this convention; `README.md`'s documented startup steps via
  `./deploy/docker-compose.yml` apply here identically).
