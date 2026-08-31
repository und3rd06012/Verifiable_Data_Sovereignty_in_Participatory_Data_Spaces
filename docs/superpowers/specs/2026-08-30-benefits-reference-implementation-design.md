# BENEFITS Reference Implementation: Design

Date: 2026-08-30
Status: approved in brainstorming, pending spec review

## 1. Purpose

Build a reference implementation of the BENEFITS participatory data
sovereignty architecture, and produce new technical and simulation
results sufficient to support a journal article submitted to MDPI
*Systems* as an extension of the SIDS 2026 conference paper
"Verifiable Data Sovereignty for Vulnerable Populations in
Participatory Data Spaces" (EU Horizon Europe GA 101179032).

No BENEFITS code exists. This is a greenfield build.

### 1.1 What the journal extension rests on

New evidence is technical evaluation plus governance simulation. No new
human subjects data is collected, so no ethics approval is required and
no fieldwork timeline applies.

The manuscript must not attribute the conference paper's existing
results (six co-design workshops with 47 participants, and the n=23
prototype evaluation showing trust 2.1 to 3.8 and +35% completeness) to
this implementation, and must not claim pilot deployment. The
implementation is presented as the reference implementation of the
published architecture. MDPI requires substantial extension over, and
citation of, the conference version.

### 1.2 Scope boundary

In scope: participant web client, steward portal, simulated device
fleet, EDC-based community connector, experiments, figures, manuscript.

Out of scope for now: the React Native application described in the
conference paper. It is deferred to a follow-on phase after the
manuscript is complete. The manuscript will state that the reference
implementation uses a PWA client implementing the same three tier
architecture, with the native application noted as separate production
work.

Also out of scope: hardening the differential privacy mechanism against
floating point side channels. One future work sentence covers it.

## 2. Approach

Three approaches were considered:

- A. Single TypeScript monorepo with shared consent and governance
  cores, Python confined to offline analysis.
- B. Polyglot service oriented stack with a Python privacy service and a
  simulation driving the system over HTTP.
- C. Build the tier 3 release boundary on Eclipse Dataspace Components
  (EDC), a real IDSA connector implementation.

**C was chosen.** It makes the conference paper's central framing
literal rather than analogical: the community steward gate becomes an
ODRL policy constraint evaluated by an IDSA conformant connector, so a
community acts as a first class policy authority using the same
machinery organisations use.

The cost of C is that consent semantics exist in two languages. Section
7 describes how that is paid for.

### 2.1 Pinned dependency

EDC is pre 1.0 and ships breaking changes on a roughly quarterly minor
cadence. Version 0.18.0 (published 2026-06-30) alone changed the
default to data plane signaling, moved identity claims into the `sub`
claim, and replaced role based with scope based authorization.

The build pins EDC to **0.18.0** exactly, vendors the Gradle wrapper,
and records the pin in the manuscript's reproducibility section.

### 2.2 Verified host toolchain

Docker 29.1.3 with a reachable daemon, Docker Compose v2.30.3, Node
v22.16.0, npm 10.9.2, Python 3.12.3, OpenJDK 17 and 21 both present
(21 default), 16 cores, 30 GB RAM. Gradle is not installed and is not
needed, since EDC ships a wrapper.

## 3. Architecture

EDC is a server side connector and covers nothing on the device tier.
It replaces the tier 3 release boundary only.

EDC provides: the ODRL policy model, a policy engine with pluggable
constraint functions, contract negotiation over the Dataspace Protocol,
data plane and signaling, participant identity via DIDs and verifiable
credentials, catalog publishing, and an event framework.

EDC provides nothing for: the offline first device tier, the participant
client, the steward portal, k-anonymity and differential privacy, or
participant verifiable audit chains.

```
TIER 1  participant device            TypeScript
        PouchDB, consent-core
        S1 gate as CouchDB validate_doc_update

TIER 2  synchronisation               CouchDB (Docker)
        filtered replication

TIER 3  community connector           Java, EDC 0.18.0
        S2 steward quorum as an ODRL constraint function
        k-anonymity and DP as a data plane stage
        audit chain fed by EDC events
        steward portal against the Management API
```

### 3.1 The three publishable ideas

**One policy model, two enforcement points.** A participant authors
consent once as a per field rule set. That single model compiles two
ways: into the CouchDB validation function that blocks a record from
leaving the device, and into ODRL constraints that EDC evaluates at the
release boundary.

**The steward gate is an ODRL constraint, not an application check.** A
`benefits:stewardQuorum` constraint function is registered in EDC's
policy engine. Contract negotiation cannot complete without a 2 of 3
quorum, so community veto is enforced by the same machinery as every
other dataspace policy.

**The audit log is fed by the connector, not written by hand.** An
extension subscribes to EDC events for every negotiation, policy
decision, and transfer, and commits them to the audit structure. The
log therefore records what the connector actually did. An audit log the
application writes about itself is weaker evidence.

Shamir secret sharing over the payload content key is retained as a
second lock on release, because a policy engine the operator runs is
not the same as cryptographic impossibility, and that difference is
what a skeptical reviewer will press on.

## 4. Data model

Five entities: consent grant, contribution record, release request,
steward authorization, audit entry. Everything else is derived.

### 4.1 Consent grant

```
ConsentGrant {
  field:          FieldId
  purpose:        PurposeId
  recipientClass: community | provider | statistics
  grantedAt:      ISO8601
  expiresAt:      ISO8601 | null
}
```

Recipient classes map one to one onto the three pictogram consent cards
already validated in the co-design workshops: `community` is the lock
icon, `provider` is the service provider icon, `statistics` is the
government building icon. Preserving that mapping exactly means the
co-design results transfer to the implementation.

### 4.2 Contribution record, with consent embedded

CouchDB validation functions run in a sealed engine. They receive only
`(newDoc, oldDoc, userCtx, secObj)`, cannot import anything, and cannot
query other documents. The consent assertion therefore travels inside
each record rather than living in a separate document.

```
ContributionRecord {
  _id, _rev
  type:           "contribution"
  participantRef: PseudonymousId
  siteId:         SiteId
  collectedAt:    ISO8601
  channel:        app | sms | paper | voice
  fields:         { [FieldId]: value }
  consent:        ConsentAssertion
}
```

The validator's job is purely local: for every field present, is there a
matching unexpired grant covering the declared purpose and recipient
class? If not, reject the write. This satisfies the conference paper's
claim that a misconfigured backend still cannot accept unconsented
records.

### 4.3 Where cryptographic verification sits is an experiment

The assertion may additionally be Ed25519 signed by the device key.
CouchDB's query server has no crypto primitives, so verifying that
inside the validator requires inlining a pure JavaScript verifier into
the design document and paying it per document.

The working hypothesis is that this is too slow above modest batch sizes
and that signature verification belongs at the connector, with the
validator doing structural enforcement only. That is a measurable
claim, so both arms are built and the number is reported. See E1.

### 4.4 Consent composition

A released dataset draws on many participants whose grants differ. Its
policy is the greatest lower bound of theirs: narrowest purpose set,
narrowest recipient class, intersection of fields, earliest expiry.

Consent rule sets therefore form a meet-semilattice and dataset
composition is the meet operation. This gives the paper a small piece of
genuine formalism and gives the implementation an unambiguous rule for a
case that is otherwise decided ad hoc.

### 4.5 Compilation to ODRL

```
permission: use
  benefits:purpose         in      [service-improvement, advocacy]
  benefits:recipientClass  in      [statistics]
  benefits:fieldSet        isAnyOf [...]
  benefits:stewardQuorum   gte     2
  benefits:kAnonymity      gte     5
  benefits:epsilonBudget   lte     0.5
```

Each left operand is a constraint function registered in EDC's policy
engine. `stewardQuorum` validates the co-signed token, `epsilonBudget`
queries the budget accountant, `kAnonymity` checks the computed k of the
candidate release. Contract negotiation fails if any constraint fails.

### 4.6 Steward authorization

```
ReleaseAuthorization {
  releaseRequestId
  datasetHash        binds the token to an exact dataset
  rubricFindings     { reidentificationRisk, purposeAlignment, proportionality }
  signatures         [ { stewardId, signature, signedAt } ]   >= 2 of 3
  shamirShares       [ share ]                                 2 of 3 over content key
}
```

Governance protocol as published: participant nomination with recorded
provenance, a published decision rubric, 2 of 3 quorum with single
steward veto, and a challenge path to a cross site review panel with all
challenge records in the audit log.

### 4.7 Audit structure

A pure hash chain forces a verifying participant to hold every entry,
which leaks other participants' access events to anyone checking their
own. Instead, entries within an epoch form a Merkle tree and only epoch
roots are hash chained.

```
AuditEntry  { seq, epoch, timestamp, eventType, actor,
              datasetRef, statedPurpose, plainLanguage }
Epoch       { epochId, merkleRoot, prevEpochHash, sealedAt }
```

A participant receives their own entries, inclusion proofs, and the root
chain. They can prove their entries are present and unaltered while
learning nothing about anyone else's. This also relieves the single
writer constraint, since entries are gathered concurrently within an
epoch and only the sealing of a root is serialised.

Stated limitations: epoch sealing remains a trusted operation unless
roots are externally witnessed, and the meet operation in 4.4 will make
aggregates conservative enough that some releases become analytically
thin, which feeds the privacy budget tension already raised in the
conference paper's open challenges.

## 5. Experiments

Six headline results plus one correctness suite. Everything runs from
fixed seeds, results land as CSV, and Python generates figures from
those files so a figure cannot drift from the numbers behind it.

Two terms used throughout are fixed here so they are not reinterpreted
per experiment. **Utility** is the relative error of a query result
against the same query computed without noise and without suppression,
reported as a median over the query set with an interquartile range.
The **low end device profile** is a single CPU core throttled to
roughly 1 GHz with 1 GB of available memory, chosen to approximate the
class of handset the target population actually owns; the exact
throttle is fixed in phase 4 and recorded with the results.

### On the real implementation

**E1. Consent filter cost at the edge.** Sweep batch size 10 to 10,000
documents against field count and grant set size, in two arms:
structural validator versus signature verifying validator. Produces the
crossover number that decides where cryptographic enforcement belongs
(4.3).

**E2. Revocation under partition.** A participant revokes consent while
their device is offline with records already queued. Measure revocation
propagation latency across connectivity duty cycles matched to the
target population, and confirm the leaked record count is zero. Also
covers time to consistency and conflict rates under the published "user
preference first" resolution strategy.

**E3. Audit tamper detection and verification cost.** Inject mutated
entries, mutated roots, and reordered epochs. Detection must be 100%; a
lower number is a bug, not a result. Reportable numbers are proof size
and participant side verification time against epoch size, on a
deliberately low end device profile.

**E4. Privacy budget versus utility at real site sizes.** Sweep epsilon
0.1 to 2.0, site population 30 to 300, k from 3 to 10, over queries a
community would actually ask: counts, proportions, cross tabs by service
type. Answers the conference paper's own open challenge on privacy
budget and community data utility. Expected to be the article's most
useful figure.

**C1. Enforcement correctness suite.** Every attempted violation must be
refused at contract negotiation: missing quorum, expired grant, purpose
mismatch, budget exceeded, k below threshold. Reported as a table, one
line each. Connector negotiation latency is reported here rather than as
a headline result.

### Governance simulation

The simulation imports the real quorum logic and the real EDC constraint
functions rather than reimplementing them, so these are measurements of
the implementation under synthetic load. The manuscript states this
explicitly.

**G1. Quorum availability and deadlock.** Vary steward response latency,
absence probability, and rotation schedule. Measure release latency
distribution, deadlock rate, and timeout fraction. Answers whether 2 of
3 with rotation is workable at community scale or whether it stalls.

**G2. Consent heterogeneity versus aggregate utility.** Because
aggregates take the meet (4.4), diverse consent shrinks releasable
datasets. Sweep heterogeneity, measure usable population fraction and
resulting utility per query. Turns the lattice formalism into a
practical number and connects to G1's governance cost.

### Cut, and why

Steward conservatism as a separate study collapses into G1's parameter
space. Steward capture and turnover dynamics over time are too
speculative to defend and become a future work paragraph. Connector
negotiation latency as a headline result is real but uninteresting and
moves into C1.

### Validity

Synthetic populations are parameterised from the workshop data where it
exists (43% legal status fear, 38% institutional distrust, 61% paper
channel preference) and declared as assumptions where it does not.
Simulation results describe mechanism behaviour under stated assumptions
and are not predictions about deployments. Nothing here is offered as
validation of the existing n=23 findings, and the manuscript says so
explicitly rather than leaving a reviewer to infer it.

## 6. Repository layout

```
benefits/
  packages/                 TypeScript workspace
    consent-core/           rule model, meet operation, S1 evaluator
    consent-couchdb/        bundles consent-core into a design document
    consent-odrl/           compiles a rule set into ODRL JSON-LD
    audit-verify/           participant side Merkle and chain verification
    device-sim/             PouchDB fleet driver
  apps/
    participant-web/        consent cards, privacy dashboard, audit viewer
    steward-portal/         review queue, rubric, co-sign, veto, appeal
  connector/                Java, Gradle wrapper, EDC pinned to 0.18.0
    extensions/
      benefits-policy/      registers the benefits:* left operands
      steward-quorum/       quorum constraint, token validation, Shamir
      privacy-pipeline/     k-anonymity, Laplace DP, budget accountant
      audit-chain/          EDC event subscriber, Merkle epochs
    runtime/                assembled connector
  experiments/
    harness/                runners for E1, E2, E3, E4, C1, G1, G2
    results/                seeded CSV output
    analysis/               Python statistics and figures
  deploy/                   docker compose: CouchDB, connector, identity
  paper/                    manuscript
```

TypeScript and Java exchange nothing but JSON. The ODRL policy document
is the entire interface between `consent-odrl` and `benefits-policy`.

## 7. Paying for the two language boundary

Approach A could have claimed a single implementation of consent
semantics shared by every enforcement point. C cannot: the S1 evaluator
is TypeScript and the S2 constraint functions are Java, so the semantics
exist twice and can drift.

The mitigation is a shared conformance vector suite: a set of JSON cases
that both implementations must decide identically, run in both test
suites and in CI. It is worth doing regardless, and it converts the
weakness into a publishable artifact, since releasing N conformance
vectors and demonstrating that edge and connector enforcement agree on
all of them is a stronger claim than asserting the code was written
once.

## 8. Phasing

Each phase has a gate that must pass before the next begins. Phases 1
through 5 are test first: the conformance vectors, tamper cases, and
violation suite are specifications before they are code, which suits
this system because the correctness claims are the contribution.

This is more work than one implementation plan should carry. The plan
that follows this spec covers **phases 0 through 5**, which together
produce a working, tested system with no user interface. Phases 6 and 7
get a second plan once phase 5's gate passes, and phase 8 is a separate
piece of work driven by the ARS academic-paper skills rather than by an
implementation plan at all.

| # | Phase | Gate |
|---|---|---|
| 0 | Skeleton, pinned EDC, compose stack | `./gradlew build` green, Management API answers, CouchDB reachable |
| 1 | consent-core, conformance vectors, design doc generation | vectors pass, real CouchDB rejects unconsented writes |
| 2 | consent-odrl, benefits-policy, steward-quorum | C1 passes, negotiation refused without quorum |
| 3 | privacy-pipeline | known answer tests, budget exhaustion refuses |
| 4 | audit-chain, audit-verify | E3 tamper detection at 100% |
| 5 | device-sim fleet | E2 revocation under partition, zero leaked records |
| 6 | participant-web, steward-portal | manual click through, paper screenshots |
| 7 | E1, E4, G1, G2, figure generation | results reproduce from seed |
| 8 | Manuscript | produced with the ARS academic-paper skills |

React Native remains deferred until phase 8 completes.

## 9. Open questions carried into implementation

These are recorded rather than resolved, and each is settled by its
phase's gate rather than by guessing now.

1. Whether EDC 0.18.0's scope based authorization changes how the
   steward portal authenticates against the Management API. Settled in
   phase 0.
2. Whether pure JavaScript Ed25519 verification inside the CouchDB
   query server is fast enough to be viable at any batch size, or fails
   outright. Settled by E1 in phase 1.
3. Epoch length for the audit structure, which trades proof size against
   sealing latency. Settled by E3 in phase 4.
4. Whether the meet operation makes realistic aggregates too thin to be
   useful, which would be a finding rather than a defect. Settled by G2
   in phase 7.
