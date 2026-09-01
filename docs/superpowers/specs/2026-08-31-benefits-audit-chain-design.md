# BENEFITS Audit Chain (Phase 4): Design

Date: 2026-08-31
Status: pending review
Parent spec: docs/superpowers/specs/2026-08-30-benefits-reference-implementation-design.md
Builds on: phases 0-3, complete on branches phases-0-2 and phase-3-privacy

## 1. Purpose

Phase 4 adds the audit chain: a per-epoch Merkle tree over connector events,
epoch roots hash-chained to one another, and a participant-facing proof that
lets a device confirm its own entries are present and unaltered without
trusting the connector operator's word for it.

Section 3.1 of the parent spec states the audit log is fed by the connector,
not written by hand — an extension subscribes to EDC's event stream for every
negotiation, policy decision, and transfer, and commits what actually
happened. This phase builds that extension.

It also builds the participant-side verifier now, not in phase 6. Experiment
E3 (audit tamper detection and verification cost) measures proof size and
participant-side verification time on the low-end device profile, so the
verifier is experiment infrastructure this phase needs to produce its own
result, not a UI component that can wait.

Parameters and data model are fixed by the parent spec section 4.7:

```
AuditEntry  { seq, epoch, timestamp, eventType, actor,
              datasetRef, statedPurpose, plainLanguage }
Epoch       { epochId, merkleRoot, prevEpochHash, sealedAt }
```

## 2. Placement

A new EDC extension, `connector/extensions/benefits-audit` (package
`org.benefits.audit`), registers an `EventSubscriber` with EDC's
`EventRouter` for four confirmed event types (verified present in EDC
0.18.0's `contract-spi` and `transfer-spi` jars):

- `ContractNegotiationFinalized` — a release request cleared every ODRL
  gate (steward quorum, k-anonymity, epsilon budget) and both parties agreed.
- `ContractNegotiationTerminated` — a negotiation that had already been
  admitted into the provider's own state machine was later terminated
  (either through the management API's explicit terminate operation, or a
  post-admission failure in the negotiation lifecycle). This is narrower
  than it sounds; see section 2.2.
- `TransferProcessCompleted` — anonymised bytes were actually delivered.
- `TransferProcessTerminated` — a transfer was started but did not finish.

This is a fifth EDC extension point after policy engine, data plane,
constraint functions and the management API, and it is deliberately
independent of `benefits-privacy`: the audit chain records that a decision
happened, not what the decision computed. Coupling it to the privacy
pipeline's internals would let a future change to k-anonymity or the budget
accountant silently change what the audit log means.

### 2.1 Why not the two alternatives phase 3 rejected for a similar reason

A library invoked at release-request creation, or a step application code
calls after checking a result, would both make the audit trail something
the application asserts about itself rather than something the connector's
own event stream proves happened. Section 3.1's third publishable idea is
specifically that this log is stronger evidence than an application-written
one; an event-subscriber extension is the only placement that keeps that
claim true.

### 2.2 A disclosed limitation: policy-refused negotiations are invisible to this audit chain

Task 1's gate 0 probe (phase 4's implementation) found that EDC 0.18.0
rejects an offer whose policy fails ODRL constraint evaluation — including
every one of `benefits-policy`'s functions: `PurposeFunction`,
`RecipientClassFunction`, `FieldSetFunction`, `StewardQuorumFunction`, and
`benefits-privacy`'s `KAnonymityFunction`/`EpsilonBudgetFunction`, since all
six evaluate in the same `NEGOTIATION_SCOPE` pass — synchronously, inside
the HTTP handler for the DSP negotiation-request endpoint, before a
`ContractNegotiation` entity is ever persisted on the provider side. No
entity means no state machine, and no state machine means `EventRouter`
never fires for the rejection: not `ContractNegotiationRequested`, not
`ContractNegotiationTerminated`. Confirmed empirically (not inferred): a
real refused negotiation, forced against a running provider, produced a 400
response body ("Policy in scope contract.negotiation not fulfilled:
[Permission constraints: ...]") with zero corresponding lines in the
provider's event log, while an unrelated negotiation admitted normally and
terminated afterward through the management API's explicit `terminate`
operation produced `ContractNegotiationTerminated` exactly as expected.

**The consequence stated plainly:** every refusal case in the C1 suite
(`quorum-missing`, `quorum-one-steward-twice`, `purpose-mismatch`,
`recipient-class-mismatch`, `field-outside-grant`,
`expired-grant-field-excluded`, `k-below-threshold`, `budget-exceeded`) is
invisible to this audit chain as scoped. This is the entire class of
"policy decision" this project's steward-quorum, k-anonymity, and
epsilon-budget machinery exists to make — and it is exactly the class the
parent spec's section 3.1 implies is audited when it says the connector
"subscribes to EDC events for every negotiation, policy decision, and
transfer." That claim does not hold as originally written; the parent spec
is amended alongside this one (its own section 3.1, 4.5.1-style amendment)
to state the boundary precisely.

This is scoped narrowly on purpose. A negotiation that clears initial offer
validation and is terminated afterward — a genuine post-admission
termination, or an operator's explicit management-API termination — is
still audited; only refusal *at the initial offer-validation gate* is
invisible. A future policy re-evaluation elsewhere in a negotiation's
lifecycle, if EDC 0.18.0 has one, is not covered by this finding and is not
assumed invisible.

**Why this is disclosed rather than engineered around.** The cheapest
apparent fix, recording a refusal from inside the constraint function
itself the moment it returns `false`, was considered and rejected: a
constraint function choosing to write its own refusal into the audit log is
exactly the "application asserts about itself" pattern section 2.1 already
rejected for a different mechanism. It would convert the one thing this
audit chain is supposed to prove, that entries reflect what the connector's
own event stream recorded rather than what a component chose to self-report,
into something weaker than the parent spec's stated ambition, for the sake
of covering a gap that formed after the ambition was written down. An
audit chain that quietly claims full coverage while narrowing what it can
actually see is worse than one that draws its boundary honestly; disclosing
the gap here, in the same place phase 3 disclosed that k-anonymity
suppression is not itself differentially private, keeps that same honesty
bar.

## 3. Hash construction

### 3.1 Domain-separated Merkle tree

A naive Merkle tree that hashes leaves and internal nodes the same way
lets an attacker present an internal node's hash as if it were a leaf's
(a second-preimage forgery), and duplicating a lone leaf to balance an odd
level reintroduces the duplicate-transaction ambiguity CVE-2012-2459
documented against early Bitcoin. Phase 4 avoids both by borrowing
Certificate Transparency's domain-separation trick (RFC 6962) — a leaf
hash and an internal-node hash can never be mistaken for one another —
without adopting RFC 6962's specific recursive tree shape, which exists
to support consistency proofs across a growing tree that this design does
not need:

```
leafHash(entry)      = SHA-256(0x00 || canonicalBytes(entry))
nodeHash(left, right) = SHA-256(0x01 || left || right)
```

An odd node at any level is promoted to the next level unhashed rather than
duplicated. `MerkleTree.root(leaves)` and
`MerkleTree.proof(leaves, index)` return the root and an inclusion proof
(an ordered list of sibling hashes, each tagged with which side the
sibling sits on so a verifier need not know the tree's shape); the
standalone `MerkleTree.verify(leafHash, proof, root)` function performs no
I/O and is the one both the Java sealing code and the TypeScript participant
verifier must agree with byte-for-byte, so it is the subject of a
cross-language conformance vector exactly as `ConsentEvaluator` was in
phase 0 (conformance/README.md).

### 3.2 Canonical entry encoding

`canonicalBytes(entry)` joins the seven non-seq fields with a separator,
escaping the separator inside any field value with a leading backslash
before backslash-escaping the backslash itself — the identical
"backslash-first" rule `KAnonymity.java` established in phase 3 to prevent
a group-key collision, applied here to prevent an entry's `plainLanguage`
field from colluding with the field boundary and forging a different
entry with the same leaf hash:

```
canonicalBytes(entry) = escape(seq) | escape(epoch) | escape(timestamp)
  | escape(eventType) | escape(actor) | escape(datasetRef)
  | escape(statedPurpose) | escape(plainLanguage)
```

`escape` is byte-identical to `KAnonymity.KEY_ESCAPE`'s rule: replace `\`
with `\\`, then `|` with `\|`.

### 3.3 Epoch chaining

```
prevEpochHash(epoch) = SHA-256(canonicalBytes(epochId, merkleRoot, sealedAt))
```

using the same escape rule over three fields. The first epoch's
`prevEpochHash` is the fixed constant `SHA-256("")`, so every deployment's
chain has the same, documented genesis value rather than an implicit
null that verifiers must special-case.

## 4. Epoch sealing

### 4.1 Trigger: event count, not a timer

An epoch seals once `EPOCH_SIZE` (default 50, configurable via
`benefits.audit.epochSize`) entries have accumulated unsealed. A
count-based trigger is deterministic: E3's proof-size-versus-epoch-size
sweep drives it directly by varying the constant and replaying a fixed
event sequence, with no wall-clock coupling to make a test flaky. A timer
would also need a background thread inside the connector for a small
gain; nothing in the parent spec requires bounded sealing latency.

### 4.2 Single-writer sealing, matching the steward store's precedent

Whether `EventRouter` dispatch in EDC 0.18.0 is single-threaded per
subscriber registration is not yet known; gate 0 settles it (section 10).
Sealing is written defensively either way, as a single critical section
guarded by one `ReentrantLock` inside the extension, so correctness does
not depend on which answer gate 0 returns. This is cheaper than the budget
accountant's compare-and-swap design (section 4.1 of the phase 3 spec) because sealing
has exactly one writer by construction — the extension's own event
callback — rather than N concurrent negotiation threads racing a shared
budget. Ruling R30's precedent (single-writer replace is acceptable when
the writer is genuinely alone) applies here for the same reason it applied
to the steward store, not for the reason it did not apply to the budget
accountant.

### 4.3 What sealing does, in order

1. Append the new entry to the CouchDB-backed store with `epoch: null`.
2. If fewer than `EPOCH_SIZE` entries now have `epoch: null`, return.
3. Otherwise: read exactly the oldest `EPOCH_SIZE` null-epoch entries,
   assign them the next `epochId`, compute the Merkle root and
   `prevEpochHash` from the prior sealed epoch, write the `Epoch` document,
   then update the entries' `epoch` field via one `_bulk_docs` call.
4. If the process crashes between steps 3's `Epoch` write and its
   `_bulk_docs` call, the next sealing attempt on restart detects an
   `Epoch` document whose entries are still `epoch: null` and resumes at
   the `_bulk_docs` step rather than recomputing the root — recomputing
   after a partial crash could compute a different root if a new entry
   arrived in between, orphaning the already-written `Epoch` document.

## 5. Storage

Phases 0-3 defaulted new state to in-memory (`InMemoryStewardAuthorizationStore`,
`InMemoryBudgetAccountant`), each accepting connector-restart data loss as a
stated limitation because a live operator was assumed to re-derive or
tolerate the loss. Audit entries do not have that property: they are the
evidence the third publishable idea rests on, and a restart that erases
them is indistinguishable, to an outside reviewer, from an operator who
never kept honest records. Phase 4 therefore persists both `AuditEntry` and
`Epoch` documents to CouchDB from the start, using the same Java HTTP
client pattern `CouchDbContributionSource` established in phase 3
(`java.net.http.HttpClient`, Basic auth from `benefits.couchdb.*`
configuration, Jackson `ObjectMapper` for request bodies rather than string
interpolation).

An `AuditStore` interface (`append`, `unsealedEntries`, `sealEpoch`,
`entriesFor(participantRef)`, `epochChain()`) separates this from the
CouchDB-backed implementation, and tests inject an in-memory fake so the
Merkle and sealing logic is verified without a database — the same seam
`ContributionSource` provided in phase 3, for the same reason.

## 6. Event to entry mapping

Read from EDC 0.18.0's compiled classes directly (`javap` against
`contract-spi` and `transfer-spi`), not assumed:

`Event`, the common base class, carries no timestamp accessor — `timestamp`
is therefore stamped by the listener itself, `Instant.now()` at the moment
the event is received, not read off the event.

`ContractNegotiationFinalized` and `ContractNegotiationTerminated` both
extend `ContractNegotiationEvent`, which declares `getCounterPartyId()`
directly — that is `actor` for both. `Finalized` additionally exposes
`getContractAgreement()`, guaranteed present, whose `getAssetId()` and
`getPolicy()` give `datasetRef` and the policy to read `statedPurpose`
from. `Terminated` has no agreement (negotiation never completed), so it
reads the same two values off `getLastContractOffer()` instead, which
`ContractNegotiationEvent` also declares directly — this accessor exists
on the base class specifically so a Terminated event still has something
to read. `statedPurpose` in both cases is the value of the scalar
`benefits:purpose` constraint on that policy's permission (phase 2, ruling
R25 fixed this to a scalar `eq`, so there is exactly one value to read,
never a set).

`TransferProcessCompleted` and `TransferProcessTerminated` extend
`TransferProcessEvent`, which declares `getAssetId()` (`datasetRef`) but
carries no counter-party or policy accessor at all — only
`getContractId()`, the agreement id.

**Correlation key: agreement id, not asset id.** An earlier version of this
design correlated a transfer's `actor`/`statedPurpose` by looking up the
most recent `AuditEntry` sharing the same `datasetRef`. Task 6's reviewer
found this ambiguous and wrong whenever the same asset is negotiated more
than once — a second negotiation for the same asset (a different
participant, or a re-negotiation) shifts "most recent entry for this
asset" onto the wrong negotiation, misattributing a transfer's actor and
purpose. `getContractId()`'s value is the agreement id EDC assigns exactly
once per finalized negotiation, with no such ambiguity, and
`ContractNegotiationFinalized.getContractAgreement().getId()` is that same
value on the negotiation side — so the listener keys an in-memory cache
(`Map<String, NegotiationInfo>`, populated in `onFinalized`, read in both
transfer handlers) by agreement id instead of querying the persisted store
by asset id. `AuditStore.entriesForAsset` (added to support the rejected
approach) is removed; nothing else in this design needs it.

**Stated limitation, traded on purpose.** This cache is in-memory only,
not persisted, so it does not survive a connector restart. A transfer that
completes after a restart occurring between its negotiation finalizing and
the transfer itself will find no cached entry and fall back to
`actor = event.getParticipantContextId()`,
`statedPurpose = "unknown (no cached negotiation info for contract <id>)"`
— exactly the honest, disclosed fallback the earlier design already used
for its own no-match case, not a new failure mode. The alternative (a
persisted, restart-durable correlation store keyed by agreement id) is
strictly more robust but adds a second CouchDB-backed structure to a phase
whose data model is otherwise fixed at two document types; disclosing a
narrow, restart-only degradation was judged the better trade for a
reference implementation over widening the persisted schema for a case the
project's own experiments do not exercise (E1-E4 run fixed batches to
completion, not connector restarts mid-negotiation).

`plainLanguage` is a short, fixed-format sentence per event type, built
from the fields above (for example, `"release request approved for asset
<id>, purpose <purpose>"` for a finalized negotiation), never displaying a
raw EDC identifier alone.

## 7. Participant proof API

A participant is not a dataspace-protocol consumer, so proof delivery does
not belong on the data plane the way phase 3's release artifacts do.
Instead the audit extension registers one read-only JAX-RS resource
directly with EDC's `WebService` SPI (the same registration point EDC's
own Management API controllers use, confirmed present transitively via
`controlplane-base-bom` in gate 0):

```
GET /audit/{participantRef}
  -> { entries: [AuditEntry, ...],
       proofs:  { [seq]: MerkleProof },
       epochs:  [Epoch, ...] }
```

Returning only entries whose `actor` equals the requested `participantRef`
keeps the boundary the parent spec's section 4.7 describes: a participant
can verify their own entries and the full root chain without seeing any
other participant's entries. Authentication for this endpoint is out of
scope for phase 4 (no device identity infrastructure exists yet — the
parent spec section 3 already defers real DIDs/verifiable credentials as a
stated limitation); the endpoint accepts a `participantRef` path parameter
directly, the same trust level phase 3's aggregate transfer already
operates at.

## 8. Participant-side verifier

New package `packages/audit-verify` (TypeScript), mirroring `consent-core`'s
existing structure. Exports:

- `leafHash(entry): string` and `canonicalBytes(entry): string` — the exact
  escaping rule from 3.2, proven identical to the Java implementation by a
  shared conformance vector file under `conformance/`, following the
  existing TS-to-Java contract pattern.
- `verifyInclusion(leafHash, index, proof, root): boolean`
- `verifyChain(epochs: Epoch[]): boolean` — recomputes each
  `prevEpochHash` from its predecessor and confirms the stored value
  matches, across the whole returned chain.

This package has no CouchDB or EDC dependency; it operates purely on the
JSON the `GET /audit/{participantRef}` endpoint returns, so it is testable
with fixed fixtures and runnable in E3's low-end device profile without a
network.

## 9. Gates

**Gate 0, event and web-service probe.** Register a trivial
`EventSubscriber`, drive one negotiation and one transfer through the
existing `probe-aggregate-transfer.sh` path, and confirm: which event
types actually fire and in what order; whether `EventRouter` dispatch is
single- or multi-threaded; and that a JAX-RS resource registered through
`WebService` is reachable over HTTP in this runtime. Settles the open
questions in section 10. If the web-service registration point does not
work as expected, the recorded fallback is serving proofs through the
existing data plane as a new `DataSourceFactory` (phase 3's pattern),
accepting the participant-as-consumer fiction that implies, and that
decision gets written down rather than worked around.

**Gate 1, hash mechanism.** Known-answer tests for `leafHash`,
`MerkleTree.root`, `MerkleTree.proof` and `MerkleTree.verify`, including
odd-leaf-count trees at every parity up to `EPOCH_SIZE`, and the
cross-language conformance vectors passing in both Java and TypeScript.

**Gate 2, sealing.** A sequence of appends that crosses `EPOCH_SIZE`
produces exactly one sealed epoch with the right entries; a simulated
crash between the `Epoch` write and the `_bulk_docs` call, followed by a
restart, completes correctly without recomputing a different root
(section 4.3, point 4).

**Gate 3, chain integrity under tampering.** Mutate a stored entry, mutate
a stored root, and reorder two sealed epochs; `verifyInclusion` and
`verifyChain` must each detect 100% of these, matching E3's stated
requirement that a lower detection rate is a bug, not a result.

**Gate 4, end to end.** A real negotiation and transfer through the running
connector produce a sealed epoch; `GET /audit/{participantRef}` returns
entries and proofs for the transfer's participant only; the TypeScript
verifier, given only that HTTP response, confirms inclusion and chain
integrity with no other input.

## 10. Open questions

1. Whether `EventRouter` dispatch is single-threaded per subscriber.
   Section 4.2 makes the sealing design correct either way, but the answer
   still needs recording. Settled by gate 0.
2. Whether a JAX-RS resource can be registered against EDC's `WebService`
   SPI from an extension outside EDC's own management-api module, or
   whether that registration point is management-api-internal. Settled by
   gate 0; fallback recorded in gate 0's description above.
3. Whether EDC ever fires a transfer event for an asset with no preceding
   negotiation entry in the audit store (section 6's correlation fallback
   exists for this case; gate 0 should try to provoke it rather than
   assume it cannot happen).

## 11. Out of scope

Device identity and authentication for the proof endpoint (parent spec
section 3, mock IAM throughout). External witnessing of epoch roots
(parent spec section 4.7's stated limitation: sealing remains a trusted
operation without it). The steward portal and participant web/mobile UI
that will eventually call this endpoint (phase 6). E3's actual experiment
run and figure generation (phase 7) — this phase produces the mechanism
E3 measures, not the measurement itself.
