# Participant Database Isolation: Design

Date: 2026-09-01
Status: pending review
Amends: docs/superpowers/specs/2026-09-01-benefits-interfaces-design.md §2 ("Ruling: no
unified API gateway", unchanged, restated below for clarity) and §3 ("Ruling: single
shared CouchDB database, not one database per participant", superseded; "Read-side" --
corrected)
Parent spec: docs/superpowers/specs/2026-08-30-benefits-reference-implementation-design.md
Builds on: phase 6 (branch phase-6-interfaces, all 15 plan tasks plus a backend fix wave,
complete; this spec fixes a gap the final whole-branch review found before merge)

## 1. Purpose

Phase 6's final whole-branch review found that participants can be locked out of the
shared `benefits` database entirely (its default `_security` blocks non-admin access), and
that even fixing that bootstrap gap would violate the interfaces spec's own §3 claim:
"Participants are never handed raw read credentials against the shared database."

The review traced this to something more fundamental than a missed bootstrap step.
`packages/consent-web-client` (§7 of the interfaces spec) and `apps/participant-mobile`
both write directly to the shared `benefits` database using each participant's own
provisioned CouchDB Basic-Auth credentials, PouchDB replication for the web client, plain
HTTP `PUT` for mobile. CouchDB has no write-only permission: any credential capable of
writing to a database is, structurally, also capable of reading the entire database via
`_all_docs`, `_changes`, or any view. So the moment a participant can push their own
consent grants to the shared database, which every phase 6 client does, they can also
read every other participant's records, regardless of what the client application's own
code chooses to expose. `resolveConflicts()` in `packages/consent-web-client/src/sync.ts`
does exactly this today (`remote.allDocs({include_docs: true})` against the shared
database), ported verbatim from phase 5's device-sim, where it was safe because device-sim
runs under trusted admin credentials in a test harness, never real participant credentials.

This spec closes that gap for real, without weakening the privacy claim and without a
gateway.

## 2. Why per-participant databases, now

§3 of the interfaces spec already considered CouchDB's per-user-database pattern
(`couch_peruser`) and rejected it: "`CouchDbAuditStore` and `CouchDbContributionSource`
(phase 4/5, already reviewed and merged) scan a single shared database via `_changes`;
forking write topology per participant would mean rearchitecting audit-chain construction
to aggregate across N databases."

That rejection assumed per-participant databases would mean N databases the *backend* has
to read from. They don't have to. CouchDB's `_replicator` database lets an administrator
configure a one-way, continuous replication job per participant database, target the
existing shared `benefits` database, and let CouchDB's own replication engine do the
fan-in. `CouchDbAuditStore` and `CouchDbContributionSource` keep scanning exactly one
database, unchanged, at the source level, they were never touched by phase 6 and this
spec doesn't touch them either. The thing that changes is upstream of them: where a
participant's write *first* lands, not where the pipeline reads from.

This also means §2's "no unified API gateway" ruling is unaffected and restated as-is: no
new service sits in the request path. Replication is CouchDB's own admin-configured
server-to-server mechanism, not a gateway relaying live requests.

## 3. Topology

Each provisioned participant gets a private CouchDB database, `participant-<ref>`
(matching the naming convention `packages/consent-web-client` already uses for the
participant's *local* PouchDB, `participant-${participantRef}`, same name, now also the
name of their remote database).

`_security` on `participant-<ref>`:
```json
{"members": {"names": ["<ref>"]}, "admins": {"roles": ["_admin"]}}
```
Exactly one non-admin user, that participant, can read or write it. No one else
(including other participants) can.

A continuous, one-way `_replicator` job, created under admin credentials at provisioning
time, replicates `participant-<ref>` → `benefits`. The participant never sees or
configures this job; it runs entirely server-side. `benefits` remains the single database
`CouchDbAuditStore`, `CouchDbContributionSource`, and the privacy pipeline read, exactly as
today.

## 4. Provisioning

`StewardResource.provisionParticipant` (`connector/extensions/benefits-privacy`) currently
creates only the participant's `_users` document via `CouchDbUserProvisioner`. It gains two
more steps, in the same request:

1. Create `participant-<ref>` (`PUT /participant-<ref>`), then set its `_security` as
   above.
2. Create the replication job: a document in `_replicator` naming `participant-<ref>` as
   source and `benefits` as target, `continuous: true`, using admin credentials for both
   sides (the job itself is admin-configured infrastructure, not something the participant
   authenticates).

All three steps (user, database, replication job) happen under the same provisioning call;
if any step fails partway, the endpoint reports which step failed rather than claiming
success (matching the honest-error-handling pattern the phase 6 fix wave already
established for re-provisioning). The exact retry/rollback behavior for a partial failure
is left to the plan, this is provisioning-time, low-frequency, administered by a steward,
not a high-throughput path needing sophisticated recovery.

## 5. Identity enforcement

`consent-couchdb/src/build-design-doc.ts`'s existing `validate_doc_update` check
(`userCtx.name === newDoc.participantRef`, with the `_admin`-role exemption already added
for seed/admin writes) gets installed on each `participant-<ref>` database at provisioning
time, instead of on the shared `benefits` database as it is today.

This still matters even though `_security` already restricts who can write to
`participant-<ref>` at all: a participant could otherwise write a document into their own
database that *claims* a different `participantRef` in its content, which would then
replicate that false identity into the shared aggregate. The database-level ACL stops
*who* can write; the content check stops *what identity a write claims*, and only the
second one survives replication.

**Shared source of truth for the validation function.** The `validate_doc_update` function
body is plain JavaScript executed by CouchDB's own query server, it is not Node code, only
currently *constructed* by `build-design-doc.ts` (TypeScript). Provisioning is Java. Rather
than reimplementing the same function body in Java and letting the two drift, the function
body is extracted to one static resource (e.g. a `.js` file checked into the repo) that
both `build-design-doc.ts` and `CouchDbUserProvisioner` read and embed verbatim. The exact
file location and how each language loads it (a resource file for Java, an import/read for
TypeScript) is left to the plan.

**Defense in depth on the shared database, measured, not assumed.** `benefits`'s own
existing copy of this design doc (installed by phase 6 Task 1, unchanged) stays in place.
The implementation plan's final review measured, live, exactly what this section asked to
have measured: CouchDB's replication write path (`_bulk_docs` with `new_edits: false`) does
invoke the *target* database's `validate_doc_update`, a reject-everything function
installed on the target genuinely blocked a replicated write. But `CouchDbReplicationProvisioner`
authenticates the replication job as a CouchDB admin on both source and target (§3, §4), and
the validation function's own `_admin`-role exemption (added for seed/admin writes, above)
short-circuits before the identity check ever runs. An admin-authenticated write carrying a
forged `participantRef` was confirmed to replicate into `benefits` unchallenged.

So the mechanism fires, but provides no protection: the secondary boundary is not a real
backstop, and the entire identity guarantee rests on the primary boundary alone, the
per-participant database's own copy of the same check, which does hold (§3's `_security`
plus this section's write-side check together mean a participant can never get a forged
`participantRef` document out of their own database in the first place, so nothing forged
ever reaches the replication path to test the secondary boundary against). This is the
honest version of "defense in depth" for this design: one real, always-enforced boundary at
the source, not two independent ones.

## 6. Client changes

`apps/participant-web/src/ConsentCards.tsx`'s `remoteUrl` and
`apps/participant-mobile/lib/session.dart`'s `Session.remoteDatabaseUrl` both change from
`<couchdb>/benefits` to `<couchdb>/participant-<ref>` (URL-encoded where needed). No other
change to either file's sync logic.

`packages/consent-web-client/src/sync.ts`'s `resolveConflicts()` needs no logic change --
reading `remote.allDocs({include_docs: true})` is legitimate once `remote` is the
participant's own database, since it contains only their own data. This fixes the direct
violation the final review found as a side effect of the redesign, not a separate patch.

## 7. What does not change

- `AuditResource`, `StewardResource`, `ReleaseRequestResource`, already use the
  session-authenticated, server-side-filtered pattern this spec extends to participant
  sync; none of them ever touched participant CouchDB credentials directly.
- `CouchDbAuditStore`, `CouchDbContributionSource`, the privacy pipeline, budget accounting
 , all keep reading the single `benefits` database, untouched.
- CORS configuration (`tools/lib.sh`'s `configure_couchdb`, `provider.properties`), governs
  which origins may talk to CouchDB at all, independent of which database.
- `tools/lib.sh`'s `ensure_couchdb`, creates `benefits` and `_users` once, generically, at
  bootstrap; per-participant databases are created per-participant, at provisioning time,
  not part of generic bootstrap.

## 8. Testing

New coverage the plan must include:
- A live integration test: provision a participant, confirm `GET /benefits` with their
  credentials returns 403 (they cannot read the shared database), confirm
  `GET /participant-<ref>` with their own credentials succeeds, confirm a second
  participant's credentials get 403 against the first participant's database.
- A live integration test proving replication actually lands a write: push a document to
  `participant-<ref>`, poll `benefits` for its arrival within a reasonable timeout, confirm
  it appears with the same content.
- The forged-identity replication test from §5.
- Re-run (not just re-read) the participant-web and participant-mobile live click-throughs
  the phase 6 fix wave already performed once C1 was fixed, this time confirming "Sync
  now" actually succeeds end to end, since the C2 gap this spec fixes was the reason it
  didn't the first time.

Replication is not instantaneous. Nothing in the participant-facing apps depends on
immediate cross-participant visibility (each app only ever reads its own database), so this
introduces no new correctness issue for them. Anything reading the shared aggregate
(audit-chain construction, the privacy pipeline) already tolerates eventual consistency --
that has been true since phase 5's replication-based architecture, not new to this spec.

## 9. Amendment text

**Interfaces spec §3, "Ruling: single shared CouchDB database, not one database per
participant"** is superseded by §2-§5 above: per-participant databases are used after all,
with the backend-read topology this ruling was protecting (`CouchDbAuditStore`/
`CouchDbContributionSource` reading one database) fully preserved via admin-mediated
replication rather than avoided by rejecting per-participant databases outright.

**Interfaces spec §3, "Read-side"** is as originally written for `AuditResource` (§4)
and `StewardResource` (§5.4), both already implement it correctly. For participant sync
specifically, the mechanism is now: participants are never handed credentials to the shared
database at all (stronger than "a connector resource filters server-side", there is no
resource in this path, only a private database only they can reach), with the shared
database populated via admin-configured replication instead.

**Interfaces spec §2** is unaffected; restated for clarity: no unified API gateway is
introduced by this spec. Replication is CouchDB's own server-to-server mechanism.

## 10. Known limitations, recorded rather than left implicit

**Legacy `participantRef` values.** The implementation plan tightens `StewardResource`'s
`participantRef` validation to CouchDB's own database-name character set (lowercase
letters, digits, `-`, `_`, starting with a letter), a strict subset of the pattern used
before this spec. Any participant already provisioned under the old, looser pattern (for
example an email-shaped ref, or one with uppercase letters) can never be re-provisioned
under the new endpoint and so can never get the private database their app now requires --
their app's sync would fail with no informative error. No live deployment exists yet, so no
participant is actually stranded by this today; noted here so a future migration (re-issue
new, compliant refs for any pre-existing participant before this ships to a real
deployment) is a known, deliberate step rather than a surprise.

**Replication job health is not monitored.** `CouchDbReplicationProvisioner`'s job document
embeds the CouchDB admin credential at provisioning time. Rotating that credential silently
breaks every existing participant's replication job at once, their local writes keep
succeeding (their own database still works), but nothing reaches the shared aggregate, and
no surface (the participant app, the steward portal, the connector's own logs) reports it.
The implementation plan's final review confirmed this is not theoretical: crashed jobs with
this exact failure mode already exist on this project's own development CouchDB instance.
A real fix needs a steward-visible replication-health view, deliberately out of scope for
this spec, recorded here as known future work, not silently accepted.

**Existing replication jobs don't pick up later changes to the job body.** `ensureReplication`
is create-only: a repeat call for an already-provisioned participant is a 409-tolerated
no-op, never an update. This plan's final review added a `selector` field to the job body
(excluding `_design/*` documents from replication, see §5's amendment) after some jobs
already existed without it; those pre-existing jobs keep replicating unfiltered indefinitely
unless something explicitly deletes and recreates them (the two jobs this project's own
development instance had accumulated during this plan's live verification were deleted by
hand once this was found). The same applies to any future change to the job body. A real fix
needs `ensureReplication` to diff and update rather than only create, deliberately out of
scope here for the same reason as the health-monitoring gap above: this is provisioning-time,
low-frequency infrastructure, not a place to add update/migration machinery speculatively
before a second real need for it appears.
