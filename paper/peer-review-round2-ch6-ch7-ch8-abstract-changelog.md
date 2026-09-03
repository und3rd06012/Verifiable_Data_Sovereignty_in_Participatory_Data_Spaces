# Peer-Review Round 2 Changelog: Chapters 6-8 and Abstract

Applied against the corrected framing now in `03-system-architecture.md` (section 3.4, section 3.6) and
`05-results.md` (section 5.5, section 5.3). All numeric values (0.555, 0.995, 91.0-99.5%, starved rate
0.0, etc.) are carried over unchanged from Chapter 5; only the interpretive framing around them changed.

## Chapter 6 (Discussion)

**Fix 1 - G1 framing rewritten throughout.** Every place discussing G1 now states: Arm B is SIDS 2026's
own specified veto policy (quoted directly, with citation), not a hypothetical stricter alternative; Arm A
is the built system, which never checks vetoes; G1 measures the availability cost of enforcing a guarantee
SIDS 2026 already published and the implementation omits; the cost is driven almost entirely by vetoes
firing as designed (Arm B starved rate 0.0), not genuine starvation.

**Fix 2 - Section 6.2, "Sustainable steward governance" paragraph.** Fully rewritten. Removed "real veto
policy" vs. "hypothetical stricter policy" language and the "governance choice... was set correctly for
availability" conclusion. Replaced with the corrected story: Arm B is SIDS 2026's specified veto, quoted
directly; G1 gives the cost of closing the gap, not validation of a choice; added the starved-rate-0.0
decomposition to show the cost is mechanical (vetoes firing), not stalling.

**Fix 2 - Section 6.3 (renamed "The Steward-Quorum Gaps and What 'Verified, Not Architected' Requires").**
Added a new paragraph disclosing the veto-checking gap as a second, coordinate disclosure alongside the
existing cryptographic-envelope gap, grounded in `StewardQuorumFunction.evaluate` never reading
`authorization.vetoes()` (section 3.4) and in G1's measured cost (section 5.5). Stated explicitly that the
veto gap is the more serious of the two because it diverges from a guarantee SIDS 2026 already published,
not an internal design intent. Adjusted downstream sentences ("the steward-quorum gate is the one place...")
so the crypto-specific discussion of Albalwy/CALYPSO is scoped to the cryptographic gap only, with a new
sentence distinguishing the veto gap as "a missing check" rather than "a missing independence property."
Changed "Closing this gap" to "Closing the cryptographic gap" in the final paragraph since two gaps are now
in play.

**Fix 3 - Section 6.5 (Limitations).** Added three new paragraphs:
- Audit-chain coverage limitation, restored from Chapter 3 section 3.6 ("load-bearing"): the audit chain
  does not cover policy-refused negotiations (missing quorum, purpose mismatch, insufficient k, exhausted
  budget), because EDC 0.18.0 rejects those synchronously before any entity exists to fire an event.
- Query-time consent-scoping gap: `Contribution` originally carried no consent information, so aggregate
  queries could include contributions regardless of the owner's granted scope, until G2's harness required
  building query-time consent scoping into the production `PrivacyPipeline`. Framed as an honestly-owned
  gap that was found reactively, leaving open the possibility that similar gaps remain unfound.
- Identity limitation: EDC's mock identity module defers a full decentralized-identity stack on the
  argument that this paper's claims concern post-identification behavior, but the steward-quorum guarantee
  depends on stewards being distinct, independently-authenticated parties, so the deferral is not fully
  orthogonal to the sovereignty claims measured here.

Updated the final ("Finally...") paragraph of 6.5 to name both steward-quorum gaps (cryptographic-envelope
and veto-checking) rather than the cryptographic gap alone.

**Fix 4 - Section 6.4 (renamed "E1 and the Absence of a Crossover").** Removed the "corrective finding"
framing and the sentence "That intuition does not survive contact with the measurement," which implied a
specific misconception the paper was correcting. Replaced with a plain statement of the finding: a
crossover might in principle be expected, but the measured data show none across the swept range. Also
corrected a consistency problem the initial pass introduced: this section, and the corresponding passages in
Chapter 7, had described the signature-verification cost as though it were paid by the production
`consent-couchdb` design document. Chapter 5 section 5.1 states plainly that the signature arm exists only
inside E1's own harness, against a separate design document E1 builds and installs itself, and that "the
production S1 filter performs no per-record signature verification of any kind." Reworded both chapters so
every mention of the signature-verification finding is explicit that it is a harness measurement of what
adding per-record signature verification would cost, not a cost the deployed system currently pays.

## Chapter 7 (Conclusion)

**Fix 5 - E3 "confirms perfect tamper detection."** Removed. Replaced with: E3 measures proof-size and
verification-time growth with epoch size; tamper detection at the mechanism level was established
separately, by an earlier implementation phase's unit tests, not by E3 itself. Matches Chapter 5 section
5.3 exactly.

**Fix 6 - E2 "zero leaked records at every tested connectivity level."** Softened to "no leaked records
observed across the simulated partition scenarios actually tested."

**Fix 7 - G1 summary sentence rewritten.** Removed "the specific governance choice SIDS 2026 left open...
was set correctly for availability" entirely. Replaced with the corrected story: SIDS 2026 specified the
veto, Arm A (built) never checks it, G1 measures the real cost of closing that gap, and the cost is driven
by vetoes firing as designed rather than genuine starvation.

**Fix 8 - Mixed-picture summary now reflects two steward-quorum gaps.** The paragraph explicitly separates
the cryptographic-envelope gap (signature-count check vs. threshold decryption) from the veto-checking gap
(recorded but not enforced), noting the second is more serious because it diverges from a published
guarantee. The closing paragraph of the chapter was also updated ("in at least two places, the
steward-quorum mechanism's cryptographic envelope and its unenforced veto, a weaker one") and now mentions
the availability price of the veto guarantee alongside signature-verification time, privacy budget, and
excluded participants.

Also removed "correcting rather than confirming the intuition" language around E1's no-crossover finding in
this chapter's mixed-picture paragraph, consistent with fix 4, and qualified both mentions of
signature-verification cost in this chapter (the mixed-picture paragraph and the closing paragraph) as a
harness measurement of what per-record verification would add to the write path, since the production S1
filter performs none today, matching Chapter 5 section 5.1 exactly.

## Chapter 8 (Future Work)

**Fix 9 - Section 8.5, "That confound is what this paper closes."** Rewritten. This paper now closes only
the confound of whether the specified mechanisms work at all, under any condition (all six experiments are
simulation, per section 8.1 and Chapter 6's Limitations). The confound of whether they hold under real
field conditions remains explicitly open, and is what section 8.1 says the pilot's own instrumentation must
check.

**Fix 10 - Section 8.2 (renamed "Closing the Steward-Quorum Gaps").** Rewritten to name both gaps as future
work: (a) the veto-checking gap, framed as the more urgent of the two since it diverges from SIDS 2026's
published behavior rather than an internal design intent, and is a small fix (`StewardAuthorization`
already carries the `vetoes` list; the constraint function just needs to read it); and (b) the cryptographic
gap, requiring genuine threshold decryption (CALYPSO/MedCo comparators, unchanged from the prior draft).
The proposed follow-on experiment was extended to include a veto-enforcing variant once the veto gap is
closed.

**Fix 11 - Section 8.4 (Sustainable Steward Governance).** Rewritten to match the corrected G1 framing: G1
does not show a stricter hypothetical policy costs more than the real one; it shows what enforcing the
already-specified veto guarantee would cost, tied to section 8.2's veto gap. Tied the three-site governance
comparison to closing the veto gap first, so sites are compared against the guarantee SIDS 2026 actually
specified.

## Abstract

**Fix 12 - G1 sentence rewritten.** Replaced "The real steward veto policy fails far less often than a
stricter hypothetical alternative... evidence the governance choice the earlier design left open was set
correctly for availability" with: "Enforcing the veto guarantee the predecessor specified, which the built
system currently omits, substantially raises the measured quorum failure rate, revealing a real, previously
undisclosed gap between the published design and the implementation."

**Fix 13 - Signature-verification harness disclosure added.** There was room within the word budget, so
added a clause: "Signature verification, measured in a harness rather than the production consent filter,
dominates cost..." (this was not deprioritized).

**Fix 14 - Tamper-detection language checked.** No "100%" or "perfect tamper detection" language was present
in the abstract to begin with; nothing to change.

**Fix 15 - Word count verified.** Final abstract paragraph is 225 words (counted programmatically), within
the roughly 200-250 word target. No further trimming was needed.

## Consistency check

Every fix above traces to specific text in the corrected `03-system-architecture.md` (sections 3.4 and 3.6)
or `05-results.md` (sections 5.3 and 5.5): the SIDS 2026 veto quote, the confirmation that
`StewardQuorumFunction.evaluate` never reads `authorization.vetoes()`, the audit-chain coverage boundary,
the query-time consent-scoping fix prompted by G2, the mock-identity-module disclosure, G1's Arm A/Arm B
definitions and starved-rate-0.0 decomposition, and E3's disclosure that tamper detection was established by
an earlier unit-test phase rather than by E3 itself. No new claims were introduced beyond what those two
chapters already state.
