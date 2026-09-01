# BENEFITS Simulated Device Fleet (Phase 5): Design

Date: 2026-09-01
Status: pending review
Parent spec: docs/superpowers/specs/2026-08-30-benefits-reference-implementation-design.md
Builds on: phases 0-4, complete on branches phases-0-2, phase-3-privacy,
phase-4-audit-chain

## 1. Purpose

Phase 5 builds the offline-first participant device tier the parent spec's
Tier 1 diagram names and experiment E2 needs: a fleet of simulated devices,
each running real PouchDB, going online and offline on a schedule, queuing
writes while offline, and syncing to the real CouchDB tier when reconnected.

Its job is narrower than "build the participant app." Phase 6 builds the
real client (Flutter, a local outbox, no PouchDB replication — a deliberate,
already-recorded divergence from this phase, per the parent spec's phase 6
amendment). Phase 5 exists specifically because E2 needs real PouchDB
replication behaviour to measure against, not a mock of it: revocation
propagation latency, leaked-record count, and conflict rate under
partition are properties of an actual sync protocol, and only observable by
running one.

Phase 5's own gate proves the mechanism — zero leaked records, and the
resolution rule converging every injected conflict. The full experiment
(duty-cycle parameter sweep, target-population-matched connectivity
figures) runs in phase 7 with E1, E3's formal run, and E4, matching the
precedent phase 4 set for E3.

## 2. Revocation becomes a first-class consent-core concept

`packages/consent-core`'s `ConsentGrant` currently has only `expiresAt`, a
passive, time-based deadline. E2 needs an explicit participant action with
its own timestamp, distinct from expiry, to measure propagation latency
against. This is a small, additive change to phase 0's already-complete
package, called out explicitly because it touches prior, reviewed work.

```typescript
export interface ConsentGrant {
  field: FieldId;
  purpose: PurposeId;
  recipientClass: RecipientClass;
  grantedAt: string;
  expiresAt: string | null;
  revokedAt: string | null;
}
```

`hasGrant` in `evaluate.ts` gains one more inactive condition, mirroring the
existing `expiresAt` check exactly:

```typescript
if (g.revokedAt !== null && g.revokedAt <= nowIso) { continue; }
```

`meet()` in `meet.ts` combines `revokedAt` the same way it already combines
`expiresAt` — earliest wins, via the existing generic `earliest()` helper —
so a revocation on any one of the grants composed into a released dataset's
policy makes the composed grant inactive from that moment, without a special
case.

`revokedAt` is required, not optional, matching `expiresAt`'s own
precedent: every one of the 15 existing conformance vectors gets
`"revokedAt": null` added, and new vectors are added for the revoked case
(a grant whose `revokedAt` is in the past behaves exactly like one whose
`expiresAt` is in the past — same `no-grant:<field>` reason, since
`evaluateRecord` reports on absence of an active grant, not on which
inactivity condition applied). This mechanical batch edit is one task, not
fifteen.

## 3. Device model

New package `packages/device-sim`, mirroring `consent-core`/`consent-couchdb`'s
existing structure. A simulated device is:

- **A local PouchDB instance**, the in-memory adapter (`pouchdb-adapter-memory`)
  — this is a fleet of simulated devices in a test/experiment environment,
  not real device storage, so no IndexedDB or LevelDB backend is needed.
- **An online/offline state machine**, driven by a duty-cycle model
  (probability of being online at each simulated tick) — parameterized, not
  hard-coded, so phase 7's sweep can vary it against "connectivity duty
  cycles matched to the target population" without touching this phase's
  code.
- **A local outbox**: contribution records created while offline queue in
  the device's own PouchDB rather than failing; they push when the device
  next comes online.
- **A revoke action**: sets `revokedAt` on a specific grant in the device's
  local copy of its own consent assertion, timestamped at the moment of the
  call, independent of whether the device is currently online.

Devices push to the real CouchDB tier via PouchDB's one-directional
`replicate.to()` — the parent spec's data flow is participant-to-server
only; nothing here needs a device to pull other participants' data down, so
"filtered replication" (Tier 2, parent spec section 3) is a provider/steward
concern for a later phase, not this one. Every simulated device replicates
against the same CouchDB database `consent-couchdb`'s design document
already protects, so the real S1 gate — not a simulated one — is what
every pushed write is validated against.

## 4. The leak-prevention rule

"Leaked record" is defined precisely, because the natural reading (a write
CouchDB's `validate_doc_update` should have rejected but didn't) is not
what E2 is actually testing — the S1 gate already covers that case, and
covers it per-record: `evaluateRecord` validates each `ContributionRecord`
against its own embedded `ConsentAssertion`, captured at the moment the
record was created. A record created before a revocation legitimately had
valid consent at creation time; S1 has no way to know a later revocation
happened, and is not supposed to — the record's embedded consent is a
snapshot, not a live query.

The leak this phase actually guards against is device-side: a contribution
record sitting **unsent** in the device's own outbox at the moment of
revocation, whose embedded consent covers the field being revoked. Nothing
server-side can stop this — the record hasn't reached the server yet — so
the device's own outbox must be swept at revocation time, dropping (or
withholding) any queued-but-unsent record whose embedded consent grants
touch the revoked field/purpose/recipientClass combination. "Zero leaked
records" means: after the device finishes syncing following a revocation,
CouchDB contains no record that was in the outbox at revocation time and
whose embedded consent covered the revoked grant.

Records already synced before the revocation are not touched — that is a
retroactive-deletion question the parent spec's data model does not
address, and out of scope here. Revocation acts on what has not yet left
the device, which is the entire propagation-latency measurement E2 asks
for.

## 5. Conflicts, and how this phase produces them

A CouchDB/PouchDB conflict is a specific, narrow thing: the same document
(`_id`) edited into two revision branches, on two replicas, before either
has seen the other's edit. CouchDB does not merge these — it picks a
deterministic winner for reads but keeps the loser reachable via
`_conflicts`, and expects the application to resolve it.

A single device that is the sole writer of its own documents has nothing
to organically conflict with. Rather than simulate multiple devices per
participant to manufacture that condition, this phase injects conflicts
directly and controllably: the experiment harness, for a configurable
fraction of simulated devices, writes a second, divergent revision straight
to CouchDB (bypassing the device's own PouchDB entirely) while that
device is offline and has independently modified the same document
locally. When the device reconnects and pushes, PouchDB's replication
surfaces a genuine conflict — not a simulated one, a real revision-tree
divergence — and "conflict rate" becomes a parameter this phase's harness
controls directly, rather than an emergent property of a fleet-of-devices
simulation this phase does not build.

**Resolution: "user preference first," made precise.** When a device's
replication surfaces a `_conflicts` list on a document, the device's own
current local copy wins outright — not a field-by-field merge, the whole
document — **unconditionally, by identity, never by comparing timestamps.**
This is deliberately not a last-write-wins rule: a rival revision's claimed
edit time cannot be trusted (in this design every rival is a harness
injection standing in for an ambiguous external actor, spec section 5's own
first paragraph — never a second genuine user choice), so a timestamp-based
resolver would let a rival that merely *claims* to be more recent override
the participant's actual choice. "User preference first" means identity,
not recency, decides: whichever revision is a match for the device's own
current local document is kept, and every other conflicting revision is
discarded, regardless of what timestamp any of them carry — including
PouchDB's own revision ordering, which is not guaranteed to agree with
wall-clock recency across replicas either and is no more trustworthy a
signal than a rival's self-reported edit time. Implementation: re-write the
remote document with the device's own current local content (a fresh
revision that supersedes every existing one), read the full conflict set
via `{conflicts: true}`, and delete every other revision to converge the
document.

## 6. Gates

**Gate 0, PouchDB replication probe.** No PouchDB dependency exists yet in
this repo. Before anything else: add it, create one device, drive one real
push replication against a real CouchDB instance, and confirm the document
that was validated by `consent-couchdb`'s actual design document (not a
mock) — the same de-risking-probe shape every prior phase opened with.

**Gate 1, revocation semantics.** The updated conformance vectors (14
existing plus new revoked-grant cases) pass in `consent-core`'s own test
suite, and `consent-couchdb`'s design-document bundle (which re-bundles
`evaluate.ts`) is rebuilt and re-verified against the same vectors through
a real CouchDB `validate_doc_update`, so the revocation semantics are
proven both as a pure function and as the actual server-side gate.

**Gate 2, offline queuing and outbox sweep.** A device queues writes while
offline, a revocation mid-queue correctly drops only the records whose
embedded consent covers the revoked grant, and the device coming back
online pushes exactly the surviving records — asserted by inspecting what
actually reaches CouchDB, not by inspecting the device's own outbox state.

**Gate 3, conflict injection and resolution.** The harness-injected
conflict (section 5) produces a real `_conflicts` entry PouchDB itself
reports, the resolution rule picks the device's own local edit, and after
resolution the document has no remaining conflicting revision — checked
against CouchDB post-sync, not against in-memory device state.

**Gate 4, end to end.** A fleet of N simulated devices (N small, e.g. 5-10 —
this gate proves the mechanism, not the population-scale figure phase 7
produces), running a real duty cycle, with a configured fraction revoking
mid-partition and a configured fraction hitting an injected conflict,
completes with zero leaked records and zero unresolved conflicts.

## 7. Out of scope

The formal E2 experiment run: the duty-cycle parameter sweep matched to
the target population, the low-end device profile's throttle (both fixed
in phase 7, per the parent spec's now-corrected phasing table), and figure
generation. Retroactive deletion of already-synced records. Multi-device
simulation as a source of organic conflicts (section 5's rejected
alternative). The real participant client (phase 6, Flutter, a different
architecture by deliberate, already-recorded decision). Read-side/pull
replication and any provider-side "filtered replication" concern.

## 8. Open questions

1. Exact `pouchdb-adapter-memory` API shape against this project's pinned
   Node version — settled by gate 0.
2. Whether PouchDB's conflict-reporting API (`{conflicts: true}` on `get`,
   versus a separate `_conflicts` fetch per losing revision) matches what
   section 5 assumes — settled by gate 3.
