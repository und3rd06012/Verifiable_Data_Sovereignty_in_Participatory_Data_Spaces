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
