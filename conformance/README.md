# Conformance vectors

These vectors pin the record-level consent semantics used by the BENEFITS
architecture's two enforcement points: S1, the TypeScript evaluator bundled
into a CouchDB `validate_doc_update` document at the participant's device,
and S2, the ODRL constraint functions in the Eclipse Dataspace Components
connector at the data release boundary. What this directory actually proves
is narrower than "S1 and S2 decide the same way", and splits into three
separate claims:

1. **Record-level consent semantics are portable across languages.** The 15
   vectors here are run against S1's live TypeScript evaluator
   (`packages/consent-core/src/evaluate.ts`) and against
   `ConsentEvaluator.java`, a faithful Java transcription of the same rules.
   `ConsentEvaluator.java` is exercised only by `ConformanceVectorTest.java`;
   it is not S2 and is not invoked by the connector at runtime. See the note
   at the top of that file.

2. **The bridge from a grant set to an ODRL policy is TypeScript, not
   Java.** `packages/consent-odrl` compiles a set of grants into the ODRL
   policy that S2 evaluates. That compilation step is proven by
   `compile.test.ts`, not by this directory.

3. **Live release-boundary enforcement is ODRL constraint evaluation in
   Java.** S2's constraint functions (`PurposeFunction`,
   `RecipientClassFunction`, `FieldSetFunction`, `StewardQuorumFunction`)
   evaluate an ODRL policy against a requester's claims, a different shape
   of decision than the vectors here exercise. That enforcement is proven
   separately by the C1 case suite, whose results are in
   `docs/results/c1-consent-cases.md`.

Neither S1 implementation (TypeScript or the Java transcription) is
authoritative over the other; disagreement between them is a defect in
whichever one departs from this directory.

Each file is one vector:

    {
      "id": "v001-simple-allow",
      "description": "one field with an exactly matching unexpired grant",
      "now": "2026-08-30T12:00:00.000Z",
      "record": { ...a ContributionRecord... },
      "expected": { "allowed": true, "reason": "ok" }
    }

Reason codes are part of the contract, not diagnostics. The closed set is:

    ok                      the record is fully consented
    not-a-contribution      type is not "contribution", so S1 does not apply
    missing-consent-assertion  no consent object at all, or consent.grants
                                is absent or null
    participant-mismatch    consent.participantRef differs from record.participantRef
    malformed-fields        fields is present, is not null, and is not a
                             JSON object (for example a JSON array)
    no-grant:<fieldId>      no matching unexpired grant for that field

An empty `grants` array is not a missing consent assertion. It is a present
assertion that grants nothing, so a record carrying fields under it yields
`no-grant:<fieldId>` for the alphabetically first uncovered field, not
`missing-consent-assertion`.

All timestamps are ISO 8601 UTC with millisecond precision and a trailing Z.
Expiry comparison is lexicographic string comparison, which is only correct
for that exact format.

Three rules are easy to get wrong and both implementations must follow them
exactly, since the reason string must be byte-identical across languages:

Field evaluation order. Fields are evaluated in ascending lexicographic order
of field id. When more than one field lacks a covering grant, the reason
names the first such field in that order, not all of them. Without this rule
the reason string would depend on JSON object key order, which differs
between languages.

Grant matching. A grant covers a field only if all three of `field`,
`purpose` and `recipientClass` are equal, as exact string comparisons, to
the field id, the record's `declaredPurpose` and the record's
`declaredRecipientClass` respectively. There is no wildcard, no prefix
matching, and no case-insensitive comparison.

Validity boundaries, stated as exclusive/inclusive. A grant is active when
`grantedAt <= now` and either `expiresAt` is null or `expiresAt > now`. Both
comparisons are lexicographic string comparisons on the ISO 8601 UTC
millisecond format. In particular: `expiresAt` exactly equal to `now` means
EXPIRED, and `grantedAt` exactly equal to `now` means ACTIVE.

## The exact-instant boundary and the CouchDB integration test's wall clock

`build-design-doc.ts` bakes `new Date().toISOString()` into the bundled
CouchDB validator, because using real time is the correct production
behaviour for S1 at the participant's device. The consequence is that
`couchdb.integration.test.ts` posts each vector's `record` to real CouchDB
but has no way to pass that vector's `now`, so the validator decides
`v010-expiry-exact-instant` and `v011-granted-at-exact-instant` against
wall-clock time, not against the instant those vectors name. Both named
instants are now in the past, so both vectors degrade there into ordinary
past-expiry and past-grant cases and pass for reasons unrelated to boundary
correctness: a validator with an off-by-one error on either boundary would
still pass that test.

The exact-instant boundary is genuinely proven, but only by the two suites
that pass `now` explicitly: `packages/consent-core/test/conformance.test.ts`
and `ConformanceVectorTest.java`.
