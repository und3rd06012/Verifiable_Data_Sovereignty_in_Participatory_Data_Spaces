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
- `ContractNegotiationTerminated` — a release request was refused at any
  gate.
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

Confirmed in gate 0, not assumed here: which accessor on each of the four
event types yields `actor` (the counter-party id), `datasetRef` (the asset
id), and enough of the negotiated policy to render `statedPurpose` and a
human-readable `plainLanguage` summary. The parent spec's compiler already
emits `benefits:purpose` as a scalar constraint (phase 2, ruling R25), so
`statedPurpose` is that value read back off the negotiation's agreed
policy where the event carries one, and the string literal `"refused"` /
`"transfer failed"` for the two failure event types, which carry no agreed
policy to read a purpose from.

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
2. Exact accessor names on `ContractNegotiationFinalized`,
   `ContractNegotiationTerminated`, `TransferProcessCompleted`, and
   `TransferProcessTerminated` for counter-party id, asset id, and the
   agreed policy. Settled by gate 0.
3. Whether a JAX-RS resource can be registered against EDC's `WebService`
   SPI from an extension outside EDC's own management-api module, or
   whether that registration point is management-api-internal. Settled by
   gate 0; fallback recorded in gate 0's description above.

## 11. Out of scope

Device identity and authentication for the proof endpoint (parent spec
section 3, mock IAM throughout). External witnessing of epoch roots
(parent spec section 4.7's stated limitation: sealing remains a trusted
operation without it). The steward portal and participant web/mobile UI
that will eventually call this endpoint (phase 6). E3's actual experiment
run and figure generation (phase 7) — this phase produces the mechanism
E3 measures, not the measurement itself.
