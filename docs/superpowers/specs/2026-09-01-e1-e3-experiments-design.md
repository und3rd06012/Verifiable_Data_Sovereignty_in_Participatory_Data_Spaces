# E1 + E3 experiments: consent filter cost and audit tamper detection

## 1. Purpose and scope

This is the first of several sub-projects that make up phase 7 (`E1, E4,
G1, G2, figure generation`, per the parent spec's phasing table, section
8) of `docs/superpowers/specs/2026-08-30-benefits-reference-implementation-design.md`.
Phase 7's scope was decomposed rather than written as one spec: this
sub-project covers **E1** (consent filter cost at the edge) and the
**formal E3 experiment** (audit tamper detection and verification cost).
E3 was moved into phase 7 by the phasing table's row 4 correction note
even though phase 4 (`audit-chain`) already exists and already proved
100% tamper detection at the mechanism level (boolean pass/fail, phase
4's own spec, Gate 3). E3-full adds the measurement phase 4 deliberately
deferred: a parameter sweep with proof-size and participant-side
verification-time figures on a low-end device profile.

E4, G1, G2, and figure generation are out of scope here and will each
get their own spec. Figure generation in particular is deferred until
more than one experiment has produced seeded CSV output, so the
figure-generation spec has real data to design against rather than a
guess.

Parent spec section 9 lists two open questions this sub-project settles:
"whether pure JavaScript Ed25519 verification inside the CouchDB query
server is fast enough to be viable at any batch size, or fails outright"
(item 2) and "epoch length for the audit structure, which trades proof
size against sealing latency" (item 3). Both entries say "settled ... in
phase 1" and "settled ... in phase 4" respectively; those phase numbers
predate the phasing table's phase-4 correction note that moved the
formal experiments here, and are stale. This sub-project is where both
are actually settled.

## 2. Repository layout

Adds the `experiments/` tree the parent spec's section 6 already names,
scoped to what this sub-project needs:

```
experiments/
  harness/
    e1/                    Node/TypeScript: signature design doc,
                            synthetic data generator, sweep driver
    e3/                    Java: seeded fixture generator, timing
                            instrumentation around MerkleTree
                            Node/TypeScript: participant-side verify
                            timing driver, using @benefits/audit-verify
  results/
    e1/                    seeded CSV output
    e3/                    seeded CSV output
```

`experiments/harness/e1` becomes an npm workspace member (root
`package.json`'s `workspaces` array gains `"experiments/harness/*"`),
so it can depend on `@benefits/consent-core` the same way every other
workspace package does. `experiments/harness/e3`'s Java side is a new
Gradle module, `:experiments:harness:e3`, added to `settings.gradle.kts`
alongside the existing `connector:extensions:*` modules; it depends on
`connector:extensions:benefits-audit` to reuse `MerkleTree` directly
rather than reimplementing it. Its Node side is a second, separate
workspace member, `experiments/harness/e3-verify`, depending on
`@benefits/audit-verify`.

## 3. E1: consent filter cost at the edge

### 3.1 What actually gets measured

S1 enforcement is entirely server-side: `packages/consent-couchdb`
bundles `consent-core`'s evaluator into a `validate_doc_update` function
installed on each participant's own CouchDB database (per
`docs/superpowers/specs/2026-09-01-participant-database-isolation-design.md`).
Nothing re-runs that check on-device; `device-sim`'s PouchDB layer
writes locally and finds out about a rejection only when it tries to
replicate. "At the edge" in the parent spec's E1 description means at
the boundary of the participant's own database, which is where the real
enforcement cost is actually paid. E1 therefore measures real HTTP
writes against a real CouchDB instance with each candidate design
document installed, not an isolated benchmark of the validator function
run outside the query server. The query server is its own JavaScript
engine (`deploy/couchdb-engine-probe.md`), and open question 2 is
specifically about that engine, not about Node.

### 3.2 Three arms

The parent spec's section 4.3 and section 5 describe two arms
(structural, signature-verifying). This design adds a third, **as
additional instrumentation, not a replacement for either named arm**:

1. **Baseline**: no `validate_doc_update` at all. Isolates CouchDB's
   own HTTP and storage overhead so the other two arms' numbers can be
   read as incremental validator cost, not conflated with request
   overhead that has nothing to do with consent enforcement.
2. **Structural**: `packages/consent-couchdb`'s existing
   `buildDesignDoc()`, unchanged, installed as-is.
3. **Signature-verifying**: a new design document, described below,
   that performs the same structural check and additionally verifies an
   Ed25519 signature over the record. Parent spec section 4.3: "The
   assertion may additionally be Ed25519 signed by the device key... verifying
   that inside the validator requires inlining a pure JavaScript
   verifier into the design document and paying it per document." The
   signature check is additive to structural enforcement, not a
   replacement for it. A design that only checked a signature and
   skipped field-level consent logic would answer a different, less
   interesting question.

Each arm runs the full sweep, three swept dimensions, values fixed here
(the parent spec gives the batch-size range and names field count and
grant-set size as swept dimensions without fixing their values):

- **Batch size:** 10, 50, 100, 500, 1000, 5000, 10000 documents per
  batch, log-spaced across the parent spec's stated 10-to-10,000 range.
- **Field count:** 1, 5, 20 fields per record's `fields` object,
  spanning a minimal record up to one wider than any real record this
  project's conformance vectors use.
- **Grant-set size:** 1, 10, 50 grants in the record's embedded
  `ConsentAssertion`. `hasGrant()` in `consent-core/src/evaluate.ts`
  scans this list linearly per field, so grant-set size is exactly the
  parameter that determines how that scan's cost grows.

The full sweep is the cross product of these three dimensions (7 x 3 x 3
= 63 points) times the three arms, each repeated for a fixed number of
trials (3) to give the results file a basis for reporting variance, not
just a single point per configuration.

### 3.3 The signature-verifying design document

**Scope guard:** this design document exists only in
`experiments/harness/e1/`. It is never installed by
`CouchDbParticipantDatabaseProvisioner` or any other production
provisioning path, and nothing in `connector/` or `apps/` references it.
It answers a research question about where cryptographic enforcement
could sit; it is not a candidate implementation of that enforcement.

**Envelope.** The harness's synthetic contribution records add two
fields beyond the real `ContributionRecord` shape (`packages/consent-core/src/types.ts`
is not modified): `devicePublicKey` (the signer's Ed25519 public key,
base64) and `signature` (base64). The signed payload is the canonical
JSON serialization of the record with `_id`, `_rev`, `devicePublicKey`,
and `signature` excluded, i.e., exactly the participant-authored
content (`participantRef`, `siteId`, `collectedAt`, `channel`,
`declaredPurpose`, `declaredRecipientClass`, `fields`, `consent`).
Canonical serialization sorts object keys recursively (arrays keep their
order) before `JSON.stringify`, since plain key-insertion order is not a
stable signing basis across two JS engines (Node, generating the data,
and the CouchDB query server, verifying it).

Key management is deliberately out of scope: the public key travels on
the document itself rather than being resolved from a trusted registry.
This measures verification *cost*, which is what E1 asks; it says
nothing about verification *security*, which a production design would
need to address separately. This is stated here so the number is not
misread as a security claim.

**Crypto library**, verified live against this exact toolchain before
being written into this spec (not assumed from documentation):
`@noble/ed25519@3.2.0` for curve operations plus `@noble/hashes@1.8.0`
for the SHA-512 it needs (noble v3 takes its hash function as an
injected dependency rather than binding to Node's `crypto` module,
which is exactly the portability this needs). Bundled together via
esbuild at `target: 'es2020'` (not `'es5'`, see below), the pair produces
a self-contained ~30KB bundle with no external dependencies, confirmed
by an actual sign/verify/tamper-detect round trip. The
`packages/consent-couchdb` production bundle stays at `es5`; this bundle
target change is local to `experiments/harness/e1`'s own esbuild
invocation and touches nothing under `packages/`.

**Why `es2020`, not `es5`:** modern pure-JS Ed25519 implementations,
`@noble/ed25519` included, use `BigInt` arithmetic, and esbuild cannot
lower `BigInt` literals to `es5`; it refuses to build. Raising the
target is safe here specifically because `deploy/couchdb-engine-probe.md`
already confirmed, against the real pinned CouchDB image, that the query
server supports `BigInt` directly. The probe doc's own conclusion states
the project's `es5` constraint on `validate_doc_update` bundles was
"precautionary rather than load bearing" for exactly this reason, with
object spread as the one construct that still genuinely fails (rejected
by CouchDB's own source parser, not the engine), and this bundle does
not use object spread, confirmed by the same live check.

**First implementation task is a live spike, not a leap:** build this
design document, `PUT` it into a real CouchDB database, and confirm one
correctly-signed synthetic document validates and one tampered-signature
document is rejected with `forbidden`, before any sweep harness code is
written on top of it. This follows the project's established practice
of confirming CouchDB behavior against the real running instance rather
than trusting the bundling analysis above alone: the analysis says the
bundle *builds* and *runs standalone*; the spike confirms it *behaves
correctly inside the query server specifically*.

### 3.4 Isolation from the shared dev CouchDB instance

E1 needs a throttled CouchDB instance (section 5 below); the ordinary
development CouchDB every other test and tool in this repo depends on
must stay untouched and unthrottled. `experiments/harness/e1` therefore
brings up its own CouchDB container, on a different port, via a
dedicated compose file (`experiments/harness/e1/docker-compose.e1.yml`)
using the same pinned image digest as `deploy/docker-compose.yml`, torn
down and recreated fresh per sweep run so results are not affected by a
prior run's leftover databases or design documents.

### 3.5 Output

One CSV per arm at `experiments/results/e1/<arm>.csv`, columns: `seed`,
`trial`, `batch_size`, `field_count`, `grant_set_size`, `wall_time_ms`,
`throttle_cpus`, `throttle_memory_mb`, `host_cpu_model`. `wall_time_ms`
is the batch's total elapsed time for that write batch, measured
client-side around the batch of HTTP `PUT`s. `trial` is 0, 1, 2 for the
three repeats of each configuration.

## 4. E3-full: audit tamper detection and verification cost

### 4.1 What phase 4 already proved, and what this adds

`MerkleTree`, `MerkleProof`, and `EpochSealer`
(`connector/extensions/benefits-audit/src/main/java/org/benefits/audit/`)
are correct and already tested at the boolean level: wrong leaf, mutated
root, tampered proof step each fail to verify
(`MerkleTreeTest.java`), and `packages/audit-verify`'s
`verifyChain()` catches a reordered or mutated-root epoch
(`chain.test.ts`). E3-full does not touch any of that logic. It adds
timing and size instrumentation around calls that already exist, and a
seeded parameter sweep over epoch size, the parameter the parent spec
names explicitly ("epoch length for the audit structure, which trades
proof size against sealing latency").

### 4.2 Swept parameter

Epoch size: 10, 50, 100, 500, 1000, 5000 entries per epoch, log-spaced,
matching E1's spacing convention above, and wide enough to show proof
size's expected log-linear growth against epoch size clearly. For each
epoch size, the harness builds one tree, then samples proofs at 10
evenly-spaced leaf indices within it (or every leaf, for epoch sizes
below 10) rather than every single leaf, since a leaf's proof size
depends only on tree shape and its position's depth, not on which leaf
it is.

### 4.3 Fixture generation must be seeded

The sweep's gate is "results reproduce from seed" (parent spec section
8). `EpochSealer`'s existing code path only ever gets its randomness
from `UUID.randomUUID()` for `epochId`, which is not part of any
hash input the sweep needs to reproduce, but the E3 harness's own
synthetic-entry generator (populating entry content, injected tamper
locations, and epoch boundaries for the sweep) must use a seeded
PRNG (`java.util.Random(seed)`, not `Math.random()`/`ThreadLocalRandom`)
so a given seed always produces the same fixtures. This is a new
class, `experiments/harness/e3`'s own `SeededFixtureGenerator`, not a
change to `EpochSealer` itself.

### 4.4 What gets measured, and where

Two things are measured, at different layers, matching what "reportable
numbers are proof size and participant side verification time" (parent
spec section 5) actually names:

- **Proof size**, computed server-side: for a given epoch size, build
  the tree, call the existing `MerkleTree.proof()` for a swept set of
  leaf indices, and record `steps().size()` (each step is one sibling
  hash, so proof size in bytes follows directly: `steps().size() * 32`
  for the hex-encoded SHA-256 hashes `AuditHashing` produces, recorded
  as its own column rather than assumed at analysis time).
- **Participant-side verification time**, measured on the client side of
  the boundary: `packages/audit-verify`'s `verifyInclusion()` and
  `verifyChain()`, run in Node under the low-end throttle described
  below, timed with `process.hrtime.bigint()` around the call. This is
  "participant side" because `audit-verify` is the package a
  participant's own device actually runs (`apps/participant-web`,
  `apps/participant-mobile`); the Java side only generates the fixtures
  the Node side then verifies; it is not itself timed as a headline
  number.

### 4.5 Output

`experiments/results/e3/proof-size.csv`: `seed`, `epoch_size`,
`leaf_index`, `proof_steps`, `proof_bytes`.

`experiments/results/e3/verify-time.csv`: `seed`, `epoch_size`,
`verify_kind` (`inclusion` or `chain`), `elapsed_ms`, `throttle_cpus`,
`throttle_memory_mb`, `host_cpu_model`.

## 5. Low-end device profile

Parent spec section 5: "a single CPU core throttled to roughly 1 GHz
with 1 GB of available memory." A CPU quota is not a clock frequency:
`docker run --cpus=1` gives one core's worth of whatever the *host*
machine's clock happens to be, which is not reproducible across
different developer machines from the quota number alone. This sub-project
therefore fixes the profile as a **quota**, not a frequency, and records
the host alongside it rather than pretending the quota alone
reproduces "roughly 1 GHz":

- `--cpus=1 --memory=1g` (Docker resource limits; the repo is already
  compose-based, so this reuses tooling already in place rather than
  introducing `cgroups` configuration by hand).
- `host_cpu_model` (read via the harness at run time, e.g. from
  `/proc/cpuinfo`'s `model name`) is written into every results row that
  used the throttle, so a reader of the CSV knows what "1 core" meant on
  the machine that produced these specific numbers.

This applies to E1's dedicated CouchDB container (section 3.4) and to
E3's Node verification process (section 4.4); both are the actual
constructs standing in for participant hardware, matching the parent
spec's framing ("chosen to approximate the class of handset the target
population actually owns") for each experiment respectively. It is not
applied to E3's Java fixture-generation side, which is server-side
tooling, not a stand-in for a participant's device.

## 6. Non-goals

- Figure generation. Deferred to its own sub-project once more than one
  experiment has produced seeded CSV output to design a figure pipeline
  against.
- E4, G1, G2. Each gets its own spec.
- Any change to production enforcement code (`packages/consent-couchdb`,
  `connector/extensions/benefits-privacy`, `connector/extensions/benefits-audit`'s
  non-test sources) beyond the new Gradle module dependency described in
  section 2. E1's signature-verifying design document and its signed
  envelope are experiment-only, per the scope guard in section 3.3.
- Real device key management or public-key trust resolution for E1's
  signature arm (section 3.3 already states this is a cost measurement,
  not a security design).

## 7. Global constraints carried from the parent project

- No em-dashes in code, docs, or commits.
- No LLM/AI-tooling attribution in commits.
- No emojis in code or logs.
- Verify CouchDB (and, here, esbuild/bundling) behavior live against the
  real pinned image/toolchain rather than trusting documentation alone:
  every crypto-library and bundling claim in section 3.3 was checked
  this way before being written down.
- Dependency versions are real, registry-verified versions
  (`@noble/ed25519@3.2.0`, `@noble/hashes@1.8.0`), not guessed.
