# BENEFITS reference implementation

A reference implementation of the consent enforcement described in
"Verifiable Data Sovereignty for Vulnerable Populations in Participatory Data
Spaces" (SIDS 2026). It demonstrates that one set of consent rules is enforced
at two independent points:

- **S1, at the participant's device.** A TypeScript evaluator bundled into a
  CouchDB `validate_doc_update` document, so the database itself refuses a
  record carrying a field the participant did not consent to share.
- **S2, at the release boundary.** ODRL constraint functions inside an Eclipse
  Dataspace Components connector, so aggregated data reaches an external
  requester only when a quorum of distinct community stewards has co-signed and
  the requester's purpose, recipient class and requested fields all match.

A directory of shared JSON conformance vectors keeps the two languages
identical. See `conformance/README.md` for the contract, and
`docs/decisions-phases-0-2.md` for the decisions taken while building it.

This covers phases 0 through 3 of the implementation plan. Phase 3 adds the
privacy pipeline: k-anonymity grouping and suppression, Laplace noise
calibrated to a per-site epsilon budget, an atomic budget accountant, and
connector-side enforcement of both `benefits:kAnonymity` and
`benefits:epsilonBudget` constraints alongside the phase 0-2 consent
constraints. The audit chain, device fleet, user interfaces and experiments
are later phases and are not present.

## Requirements

Docker, Node 20 or later, and a JDK 17. Gradle is not required: the wrapper is
vendored. Eclipse Dataspace Components is pinned to 0.18.0 and CouchDB is
pinned by image digest, both deliberately, for reproducibility.

## Setup

    npm install
    npm run build --workspaces --if-present
    ./gradlew shadowJar
    docker compose -f deploy/docker-compose.yml up -d

Wait for CouchDB, which takes a few seconds:

    until curl -sf http://admin:benefits@localhost:5984/_up >/dev/null; do sleep 1; done

## Running the tests

    npm test --workspaces --if-present   # 66 tests, includes a live CouchDB integration suite
    ./gradlew test                       # run to see current counts; includes the Java
                                          # conformance runner and the privacy pipeline suite

The conformance vectors in `conformance/vectors/` are run by BOTH suites. That
is the point: the same 15 cases must produce byte-identical verdicts in
TypeScript and in Java.

`./gradlew test` requires a live, reachable CouchDB at `localhost:5984`
(`admin:benefits`): `CouchDbContributionSourceIntegrationTest`, in the privacy
module, connects to it directly rather than skipping when it is absent, so
the whole test run fails with a connection error if CouchDB is not up. Start
it first with `docker compose -f deploy/docker-compose.yml up -d`.

## Seeing S1 refuse an unconsented write

This posts a record carrying a field no grant covers, and CouchDB refuses it:

    ./tools/demo-s1.sh

## Seeing S2 refuse a release without a steward quorum

This starts two connectors and runs nine real contract negotiations over the
Dataspace Protocol, one per case, then writes a results table to
`docs/results/c1-consent-cases.md`:

    ./tools/violations.sh

It takes a couple of minutes because each case restarts both connectors and an
EDC shutdown takes roughly 20 seconds. One case is expected to succeed
(`quorum-met`); the other eight are expected to be refused, each for a
different and specifically verified reason. Two of the eight
(`k-below-threshold`, `budget-exceeded`) test the phase 3 constraint functions
against a directly-seeded `ReleaseArtifact` rather than one a live
`PrivacyPipeline` run produced; see the scope note near the top of
`docs/results/c1-consent-cases.md`.

To run a single negotiation instead:

    ./tools/negotiate.sh

## Seeing a data transfer actually move bytes

Two scripts exercise the data plane, which the negotiation suite above never
does:

    ./tools/probe-transfer.sh            # a trivial data source, proving the data plane
                                          # can move bytes at all, with a recognisable marker
    ./tools/probe-aggregate-transfer.sh  # the real BenefitsAggregate data source, proving a
                                          # stored anonymised artifact is served byte-identical
                                          # across two separate transfers of the same release

## Ports

CouchDB uses 5984. The provider connector uses 19191 to 19196 and 19291 (the
data plane's public API); the consumer uses 29191 to 29196 and 29291. The
probe scripts' HTTP sink uses 4000. All must be free before running the
negotiation suite.

## Shutting down

    docker compose -f deploy/docker-compose.yml down
