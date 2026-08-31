# Conformance vectors

These vectors are the contract between the two consent enforcement points.
The TypeScript evaluator in `packages/consent-core` and the Java constraint
functions in `connector/extensions/benefits-policy` must decide every vector
identically. Neither implementation is authoritative; disagreement is a
defect in whichever one departs from this directory.

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
    missing-consent-assertion
    participant-mismatch    consent.participantRef differs from record.participantRef
    no-grant:<fieldId>      no matching unexpired grant for that field

All timestamps are ISO 8601 UTC with millisecond precision and a trailing Z.
Expiry comparison is lexicographic string comparison, which is only correct
for that exact format.
