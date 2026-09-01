# Participant Database Isolation: Design

Date: 2026-09-01
Status: pending review
Amends: docs/superpowers/specs/2026-09-01-benefits-interfaces-design.md §2 ("Ruling: no
unified API gateway" — unchanged, restated below for clarity) and §3 ("Ruling: single
shared CouchDB database, not one database per participant" — superseded; "Read-side" —
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
provisioned CouchDB Basic-Auth credentials — PouchDB replication for the web client, plain
HTTP `PUT` for mobile. CouchDB has no write-only permission: any credential capable of
writing to a database is, structurally, also capable of reading the entire database via
`_all_docs`, `_changes`, or any view. So the moment a participant can push their own
consent grants to the shared database — which every phase 6 client does — they can also
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
database, unchanged, at the source level — they were never touched by phase 6 and this
spec doesn't touch them either. The thing that changes is upstream of them: where a
participant's write *first* lands, not where the pipeline reads from.

This also means §2's "no unified API gateway" ruling is unaffected and restated as-is: no
new service sits in the request path. Replication is CouchDB's own admin-configured
server-to-server mechanism, not a gateway relaying live requests.

## 3. Topology

Each provisioned participant gets a private CouchDB database, `participant-<ref>`
(matching the naming convention `packages/consent-web-client` already uses for the
participant's *local* PouchDB, `participant-${participantRef}` — same name, now also the
name of their remote database).

`_security` on `participant-<ref>`:
```json
{"members": {"names": ["<ref>"]}, "admins": {"roles": ["_admin"]}}
```
Exactly one non-admin user — that participant — can read or write it. No one else
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
is left to the plan — this is provisioning-time, low-frequency, administered by a steward,
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
body is plain JavaScript executed by CouchDB's own query server — it is not Node code, only
currently *constructed* by `build-design-doc.ts` (TypeScript). Provisioning is Java. Rather
than reimplementing the same function body in Java and letting the two drift, the function
body is extracted to one static resource (e.g. a `.js` file checked into the repo) that
both `build-design-doc.ts` and `CouchDbUserProvisioner` read and embed verbatim. The exact
file location and how each language loads it (a resource file for Java, an import/read for
TypeScript) is left to the plan.

**Defense in depth on the shared database.** `benefits`'s own existing copy of this design
doc (installed by phase 6 Task 1, unchanged) stays in place. Whether CouchDB's replication
write path (`_bulk_docs` with `new_edits: false`) actually invokes the *target* database's
`validate_doc_update` is a real, unverified detail — CouchDB's documentation says it does,
but this project verifies behavior live rather than trusting documentation (established
practice throughout phase 6's review process). The plan includes a test that attempts to
replicate a document with a forged `participantRef` into a participant's own database and
confirms whether it is rejected there (the primary, always-enforced boundary) and separately
whether a forged document that somehow reached `benefits` directly would be rejected there
too (the secondary boundary, empirically confirmed either way — if replication doesn't
invoke it, that's documented as a known gap covered entirely by the primary boundary, not
silently assumed).

## 6. Client changes

`apps/participant-web/src/ConsentCards.tsx`'s `remoteUrl` and
`apps/participant-mobile/lib/session.dart`'s `Session.remoteDatabaseUrl` both change from
`<couchdb>/benefits` to `<couchdb>/participant-<ref>` (URL-encoded where needed). No other
change to either file's sync logic.

`packages/consent-web-client/src/sync.ts`'s `resolveConflicts()` needs no logic change —
reading `remote.allDocs({include_docs: true})` is legitimate once `remote` is the
participant's own database, since it contains only their own data. This fixes the direct
violation the final review found as a side effect of the redesign, not a separate patch.

## 7. What does not change

- `AuditResource`, `StewardResource`, `ReleaseRequestResource` — already use the
  session-authenticated, server-side-filtered pattern this spec extends to participant
  sync; none of them ever touched participant CouchDB credentials directly.
- `CouchDbAuditStore`, `CouchDbContributionSource`, the privacy pipeline, budget accounting
  — all keep reading the single `benefits` database, untouched.
- CORS configuration (`tools/lib.sh`'s `configure_couchdb`, `provider.properties`) — governs
  which origins may talk to CouchDB at all, independent of which database.
- `tools/lib.sh`'s `ensure_couchdb` — creates `benefits` and `_users` once, generically, at
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
  the phase 6 fix wave already performed once C1 was fixed — this time confirming "Sync
  now" actually succeeds end to end, since the C2 gap this spec fixes was the reason it
  didn't the first time.

Replication is not instantaneous. Nothing in the participant-facing apps depends on
immediate cross-participant visibility (each app only ever reads its own database), so this
introduces no new correctness issue for them. Anything reading the shared aggregate
(audit-chain construction, the privacy pipeline) already tolerates eventual consistency —
that has been true since phase 5's replication-based architecture, not new to this spec.

## 9. Amendment text

**Interfaces spec §3, "Ruling: single shared CouchDB database, not one database per
participant"** is superseded by §2-§5 above: per-participant databases are used after all,
with the backend-read topology this ruling was protecting (`CouchDbAuditStore`/
`CouchDbContributionSource` reading one database) fully preserved via admin-mediated
replication rather than avoided by rejecting per-participant databases outright.

**Interfaces spec §3, "Read-side"** stands as originally written for `AuditResource` (§4)
and `StewardResource` (§5.4) — both already implement it correctly. For participant sync
specifically, the mechanism is now: participants are never handed credentials to the shared
database at all (stronger than "a connector resource filters server-side" — there is no
resource in this path, only a private database only they can reach), with the shared
database populated via admin-configured replication instead.

**Interfaces spec §2** is unaffected; restated for clarity: no unified API gateway is
introduced by this spec. Replication is CouchDB's own server-to-server mechanism.
