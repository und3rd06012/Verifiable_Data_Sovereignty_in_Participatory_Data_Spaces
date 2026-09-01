# BENEFITS Interfaces (Phase 6): Design

Date: 2026-09-01
Status: pending review
Parent spec: docs/superpowers/specs/2026-08-30-benefits-reference-implementation-design.md
Builds on: phases 0-5, complete on branches phases-0-2, phase-3-privacy,
phase-4-audit-chain, phase-5-device-fleet

## 1. Purpose

Phase 6 builds the three real interfaces the parent spec names — participant
web client, steward portal, and the Flutter mobile client — and the backend
surface they actually require to be real, not demonstration shells. That
backend surface does not fully exist yet:

- Release-request creation is still test-tooling-only (`tools/run-case.sh`),
  a gap phase 3's own spec section 8.1 already recorded.
- `StewardAuthorizationStore` is in-memory, and its own doc comment states
  it uses replace, not append, semantics, with an explicit warning that a
  concurrent write path — exactly what a portal with multiple stewards
  co-signing independently is — must not be built against it until an
  atomic append operation exists.
- `AuditResource` has no authentication at all: it trusts the
  `participantRef` path parameter outright.
- No participant-identity enforcement exists on writes to CouchDB; every
  simulated device in phase 5 wrote under shared credentials.
- The steward domain model itself has no rubric, veto, or appeal concept —
  `StewardAuthorization` today is only a dataset hash plus a list of
  co-sign signatures.

Phase 6 closes all five gaps as part of building the three clients, because
none of the three clients is real without them.

## 2. Architecture

The parent spec's Tier 1 (participant devices write straight to CouchDB,
enforced by the S1 `validate_doc_update` gate) and Tier 3 (steward/community
connector against the EDC Management API) already exist and are proven —
phase 5 proved Tier 1 end to end with real PouchDB. Phase 6 extends both
tiers with real clients rather than inserting a new backend gateway in front
of either.

**Ruling: no unified API gateway in front of CouchDB.** A gateway that
fronted Tier 1 for participant-web and mobile would mean either
reimplementing CouchDB's replication protocol server-side or dropping real
replication for the web client too — both throw away phase 5's proof that a
real sync protocol is what E2 measures against. Participant-web and
participant-mobile keep writing straight to CouchDB; steward-portal keeps
talking to the connector. This is an extension of the existing two tiers,
not a new one.

## 3. Participant identity and write enforcement

This is the piece that makes "full blown backend" mean something for a data
sovereignty paper: right now nothing stops one participant's credentials
from writing another participant's document.

**Provisioning.** Each participant gets their own CouchDB database user,
created by a steward-facing provisioning action (§5.4) at onboarding. The
credential handoff UX (QR code, magic link, manual entry) is left to the
plan — the backend-side provisioning endpoint is fixed regardless of which
the plan picks.

**Ruling: single shared CouchDB database, not one database per
participant.** CouchDB's `couch_peruser` pattern (auto-provisioned
per-user databases) is the idiomatic way to get real per-participant
isolation, and was considered. It is rejected here: `CouchDbAuditStore` and
`CouchDbContributionSource` (phase 4/5, already reviewed and merged) scan a
single shared database via `_changes`; forking write topology per
participant would mean rearchitecting audit-chain construction to
aggregate across N databases, a rearchitecture risk to already-reviewed
work that is out of proportion to what phase 6 needs. Identity is enforced
at the write layer instead, on the existing shared database.

**Write-side enforcement.** `consent-couchdb`'s design-document bundle
(`build-design-doc.ts`, which re-bundles `consent-core`'s `evaluate.ts`)
gains a new check in `validate_doc_update`, additive in the same way phase
5's `revokedAt` check was: CouchDB's `validate_doc_update` receives
`userCtx`, giving the authenticated CouchDB username for free. A write is
rejected unless `userCtx.name` equals the document's own `participantRef`
(for both `ConsentAssertion` and `ContributionRecord` documents). This is
the mechanism proven by conformance vectors in Gate 1.

**Read-side.** Participants are never handed raw read credentials against
the shared database — that would let any provisioned user read everyone's
records. Every participant-facing read goes through a connector HTTP
resource that filters server-side by `participantRef`, extending the exact
pattern `AuditResource` already established (§4), not a new one.

## 4. AuditResource gets real authentication

Today (`connector/extensions/benefits-audit/.../AuditResource.java`):
zero authentication. `forParticipant(@PathParam("participantRef") ...)`
trusts the path parameter outright — its own doc comment says filtering by
that parameter "is the whole privacy boundary this endpoint provides."

Phase 6 adds a real check: the caller presents the requested participant's
own CouchDB Basic-Auth credentials (the ones provisioned in §3) as a
header. `AuditResource` validates them by calling CouchDB's own
`/_session` endpoint (CouchDB natively validates Basic-Auth this way, no
new identity provider needed), confirms the authenticated username matches
the requested `participantRef`, and only then runs its existing
filter-by-`participantRef` logic. Reuses CouchDB's own user store, the same
reasoning as §3's write-side check.

## 5. Steward-facing backend

### 5.1 Domain model gap

`StewardAuthorization` (`connector/extensions/benefits-policy/.../StewardAuthorization.java`)
is currently just `(releaseRequestId, datasetHash, signatures, issuedAt)` —
no rubric, no veto, no appeal. The phasing table's phase 6 gate names all
four actions (review, co-sign, veto, appeal) as things the portal must do.
The exact record shape (extend `StewardAuthorization` vs. a sibling type
per action) is left to the plan; the spec fixes only that all four actions
must exist as distinct, queryable states — a veto is not merely "no
signature," and an appeal must reference the veto it contests.

### 5.2 Concurrency fix (required before any HTTP path)

`StewardAuthorizationStore.save()`'s own doc comment: *"the caller must be
the sole writer for a given release request id. Accumulating signatures
from several stewards over time, such as a portal where stewards sign
independently and concurrently, requires an atomic append operation that
does not exist yet. Such an operation must be added before any concurrent
write path is introduced."* A steward portal with multiple stewards
co-signing the same release request from separate HTTP requests is exactly
that concurrent write path. Phase 6 adds an atomic append operation (e.g.
`appendSignature(releaseRequestId, signature)`) with real concurrency
control, and `StewardResource` calls only that — never the wholesale
`save()` — once more than one signer can act on the same request.

### 5.3 Persistence

`InMemoryStewardAuthorizationStore` has no CouchDB-backed sibling, unlike
audit (`CouchDbAuditStore`) and the privacy pipeline
(`CouchDbContributionSource`). Add `CouchDbStewardAuthorizationStore`
mirroring that established pattern, so steward decisions survive a
restart. Same gap, same fix, applies to `InMemoryReleaseArtifactStore`
(§6): add `CouchDbReleaseArtifactStore`.

### 5.4 New HTTP resource: StewardResource

Registered directly against EDC's `WebService`, the same way
`AuditResource` is (a steward portal, like a participant, is not a
dataspace-protocol consumer for these actions):

- `GET /steward/queue` — pending release requests needing review
- `POST /steward/{requestId}/review` — rubric score/notes
- `POST /steward/{requestId}/co-sign`
- `POST /steward/{requestId}/veto`
- `POST /steward/{requestId}/appeal`
- `POST /steward/participants/{participantRef}/provision` — the
  onboarding action from §3: creates the participant's CouchDB user,
  returns one-time setup credentials

### 5.5 Steward portal auth

The parent spec's own open question 1 — whether EDC 0.18.0's scope-based
authorization changes how the steward portal authenticates against the
Management API — is marked "Settled in phase 0," but nothing in the
codebase implements it yet (confirmed by this phase's grounding research:
zero JAX-RS auth annotations anywhere in `connector/`). Rather than guess
the mechanism here, Gate 0 settles it empirically against the real
Management API before any portal UI is built on top of an assumption.

## 6. Release-request creation

Closes phase 3's own stated gap (spec section 8.1): today only
`tools/run-case.sh` invokes `PrivacyPipeline` against contributions, never
a live request.

New: `POST /release-requests {purpose, recipientClass, fields,
datasetSpec}`, wired to a real `CouchDbContributionSource` +
`PrivacyPipeline` run, persisting the result via the new
`CouchDbReleaseArtifactStore` (§5.3). The created release request is what
`StewardResource`'s queue (§5.4) surfaces; co-signs, vetoes, and appeals
all reference this same request id. The existing S2 constraint functions
(`PurposeFunction`, `RecipientClassFunction`, `FieldSetFunction`,
`StewardQuorumFunction`) are unchanged — they now evaluate a policy fed by
a real pipeline run instead of test-tooling-seeded data.

## 7. packages/consent-web-client (new)

Browser PouchDB (`pouchdb-adapter-idb`, not the memory adapter phase 5's
`device-sim` uses) wrapping the same write/outbox/revoke/conflict-resolution
logic phase 5 proved (`device.ts`, `sync.ts`'s identity-based
`resolveConflicts`), reimplemented for browser persistence — not a
dependency on `device-sim` itself, which stays simulation-only per its own
package comments. Real PouchDB replication is the right choice for the web
client specifically, since browser IndexedDB is a genuinely supported
PouchDB adapter, unlike Dart's ecosystem (the reason the mobile client
uses a different architecture at all — parent spec's Flutter amendment).
Consumed by `apps/participant-web`.

## 8. apps/participant-web

- Consent cards: grant/revoke via `consent-web-client`, writing under the
  participant's own provisioned CouchDB credentials (§3).
- Privacy dashboard: current grants, read from the local PouchDB replica
  directly — no separate read endpoint needed, since replication already
  keeps a synced local copy.
- Audit viewer: calls the now-authenticated `AuditResource` (§4).

## 9. apps/participant-mobile (Flutter)

- Local outbox: `sqflite` (or `Hive`) queue, porting phase 5's leak-prevention
  rule (`Device.revoke()`'s outbox sweep, `sentIds` tracking) to Dart. No
  PouchDB, no bidirectional replication, no conflict resolution — the
  already-recorded decision (parent spec §1.2): mobile costs "bidirectional
  replication and the published 'user preference first' conflict
  resolution," not the S1 enforcement claim itself.
- Sync: queued records are pushed directly to CouchDB's HTTP API (plain
  per-document `PUT`, no PouchDB library) under the participant's
  provisioned credentials. The same §3 identity check enforces every push
  regardless of how it got there — enforcement does not depend on
  replication behavior.
- Audit viewer and privacy dashboard call the same connector resources as
  participant-web (§4, §8) — shared backend, thin client.

## 10. apps/steward-portal

Review queue, rubric entry, co-sign, veto, appeal — a thin client over
`StewardResource` (§5.4), authenticated per §5.5.

## 11. Gates

**Gate 0, integration probes.** One real round trip per new integration
point, before any UI polish: (a) participant-web writes one contribution to
real CouchDB under a provisioned participant user, confirming the new
identity check enforces; (b) the Flutter outbox pushes one record over
plain HTTP to real CouchDB under a provisioned user; (c) the concrete
Management API auth mechanism for steward-portal (§5.5) is settled
empirically and one authenticated call to `StewardResource` succeeds.

**Gate 1, participant-identity write enforcement.** A write whose
authenticated CouchDB user does not match the document's `participantRef`
is rejected by the real `validate_doc_update` — proven through actual
CouchDB, matching phase 5's precedent of testing the real gate, not a mock.

**Gate 2, steward workflow.** Review, then co-sign from two genuinely
concurrent HTTP calls (proving the atomic-append fix, §5.2, actually
replaced the replace-semantics bug rather than just changing its
signature), then veto, then appeal — all verified by reading back from
`CouchDbStewardAuthorizationStore`, not in-memory state.

**Gate 3, release-request creation.** A live `POST /release-requests`
genuinely invokes `PrivacyPipeline` against real CouchDB contributions and
persists via `CouchDbReleaseArtifactStore`; the resulting artifact is what
the unchanged S2 constraint functions evaluate.

**Gate 4, end to end.** Each app's golden path — manual click-through, not
automated — matching the phasing table's own literal gate text for phase 6.

## 12. Out of scope

CouchDB database-per-participant topology (ruled against, §3). A custom
identity provider or JWT issuance — reuses CouchDB's own user auth and
whatever the EDC Management API already provides. Production-grade
credential rotation, password reset, MFA. The formal E1/E3/E4/G1/G2
experiments (phase 7). The manuscript (phase 8).

## 13. Open questions

1. Exact EDC 0.18.0 Management API auth mechanism for steward-portal —
   settled by Gate 0.
2. Exact participant-onboarding credential-handoff UX (QR code, magic
   link, manual entry) — left to the plan; the provisioning endpoint
   (§5.4) is fixed regardless of which the plan picks.
