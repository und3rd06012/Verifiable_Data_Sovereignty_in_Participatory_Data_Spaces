# Peer-Review Round 2 Changelog: Chapters 1, 2, 4

Applied against `sections/01-introduction.md`, `sections/02-related-work.md`, and
`sections/04-methodology.md`. Chapters 3 and 5 were read in full but not edited (they were
already corrected in round 1); references.bib and the SIDS 2026 source `.tex` were read but
not edited.

## Chapter 1 (Introduction)

**1. "quantifying for the first time" overstatement.**
Replaced the "quantifying for the first time what these architectural guarantees actually
cost and where they degrade" claim with a softened version: novelty is in measuring several
mechanism classes together under one built system, not in any single measurement type being
unprecedented. Explicitly notes that quorum-availability tradeoffs and privacy-budget-utility
curves have real if thin precedent per Chapter 2 (grounded in Chapter 2's own Sections 2.5 and
2.6, which cite CALYPSO, Rogers et al., and Abowd et al. as exactly this kind of precedent).

**2. G1 preview question.**
Replaced "does the quorum-and-veto policy... behave... the way its designers intended, or
would a stricter policy have been the safer choice?" with "does the built system enforce the
veto guarantee SIDS 2026's own steward-quorum policy specifies, and if not, what would
enforcing it cost in release availability?" Matches Chapter 3 Section 3.4's disclosure and
Chapter 5 Section 5.5's reframe (Arm B = SIDS 2026's own specified policy, not a hypothetical).
Also updated the E1-through-G2 preview list's one-line G1 description in paragraph 5 to the
same framing, and the threshold-cryptography discussion in Chapter 2 Section 2.6 which
contained the identical "hypothetical stricter" phrasing.

**3. "hard guarantee of zero leaked records" overstatement.**
Softened to "no leaked records observed across the simulated partition scenarios tested,"
matching Chapter 5 Section 5.2's own framing of E2 as an observed result over 150 simulated
fleet trials, not an absolute guarantee.

**4. E1 harness-only disclosure.**
Added one sentence to the E1 line in the experiment-preview paragraph: "this measurement runs
inside a harness-only design document built for the experiment, not the production S1 filter,
which performs no per-record signature verification today." Wording drawn directly from
Chapter 5 Section 5.1's own disclosure ("The signature arm exists only inside E1's harness...
The production S1 filter performs no per-record signature verification of any kind").

## Chapter 2 (Related Work)

**5. Table I privacy-mechanism claim (Section 2.1).**
Verified against SIDS 2026's own Table I (`\label{tab:comparison}` in
`[EDIT_THIS]conference_101719.tex`, rows for IDSA RAM, Gaia-X, DECODE, SPPEAR, Open Humans,
MyData, Chereja et al., McInnis et al., BENEFITS). Confirmed DECODE = "ABC cryptography",
SPPEAR = "Pseudonyms", Chereja et al. = "Risk index (PEI/PREF)", all concrete techniques, not
policy/certificate/UX categories as the original claim asserted. Rewrote the sentence: BENEFITS
is not the only row with a concrete technique; it is distinguished by combining two concrete
techniques (k-anonymity and DP) where each of those three comparators has only one.

**6. False claim that Chapter 3's connector measurements address the EDC-benchmark gap
(Section 2.3).**
Verified against Chapter 3 (no connector-level latency/throughput benchmark anywhere in
Section 3.2 or elsewhere) and Chapter 4 (C1 is explicitly "a correctness baseline, not a
performance or robustness claim"; none of E1-G2 benchmarks the connector itself). Removed the
false claim and replaced it with an accurate statement that the gap remains open in this
manuscript too, naming which boundary each of the six experiments actually targets instead
(device-side filter, audit chain, privacy pipeline, steward-quorum policy).

**7. Mironov (2017) / moments-accountant relationship (Section 2.5).**
Corrected the claim that RDP offers "substantially tighter composition than the
moments-accountant approach" (implying RDP supersedes/beats the moments accountant
specifically). Rewrote to state the accurate relationship: RDP's composition is tighter than
naive composition, and RDP recovers and generalizes the moments-accountant technique, so the
moments accountant is properly a special case of RDP accounting rather than a separate,
chronologically superseded predecessor.

**8. Gaia-X document name (Section 2.1).**
references.bib's `gaiax` entry title is "Gaia-X Architecture Document 22.10", not "Gaia-X Trust
Framework" (a mismatch inherited from SIDS 2026's own text, e.g. its abstract and Section 2).
Corrected the one prose reference to "the Gaia-X Architecture Document".

**9. Certificate Transparency / RFC 6962 (Section 2.4).**
WebSearch confirmed RFC 6962 was obsoleted by RFC 9162 ("Certificate Transparency Version
2.0", IETF Datatracker) and that Chrome has enforced CT since 2018 while Firefox added
enforcement starting with version 135 in 2025. Added the RFC 9162 supersession note and
replaced the unqualified "all major browsers now requiring it" with a dated, verifiable
statement (Chrome since 2018, Firefox since 2025).

**10. Schneier and Kelsey (1999) attribution (Section 2.4).**
Per literature-search-report.md's own description of the mechanism ("chained one-way hashes
plus forward-secure MACs"), corrected the attribution so that "unreadable [after compromise]"
is credited to the forward-secure MAC / key-evolution half and "undetectably unmodifiable" is
credited to the hash-chaining half, rather than both properties being attributed to the hash
chain alone.

**11. Rogers et al. / Abowd et al. "contested" framing (Section 2.7 closing paragraph).**
literature-search-report.md characterizes Rogers et al. as "explicit engineering tradeoffs" in
a production system and Abowd et al. as a "publicly scrutinized" budget "documented and
defended in detail against public and academic criticism." Rewrote so only Abowd et al. carries
"contested," and Rogers et al. keeps its own framing (engineering tradeoffs in a fixed,
enforced production budget).

**12. "None of the five sources... sweeps that variable" (Section 2.4).**
literature-search-report.md's own gap-analysis (point 4) names only Crosby and Wallach (log
size), CONIKS, and Merkle² (user population) as sources with an explicit empirical scale
sweep; Schneier and Kelsey and Certificate Transparency do not report a scale sweep at all.
Rewrote to name the three sources with an explicit sweep rather than claiming "none of the
five."

**13. Meta-commentary / process-narration phrases (throughout).**
Removed or rewrote into ordinary scholarly voice: "a dedicated search of the paper's own target
venue did not surface..." -> "we are aware of no connector performance study..." (2.3); "The
literature searched for this chapter does not close that concern" -> "To our knowledge, no
peer-reviewed source closes that concern" (2.5); "landing at three included sources against a
wider search" -> "with only three sources meeting inclusion criteria" (2.6); "could not be
confirmed during this search" -> "is unclear from the published description" (2.6); "Only
CALYPSO clears that bar cleanly within this search" -> dropped "within this search" (2.6);
"the floor of three sources found here... not as an exhaustive survey artificially thinned to
fit a target range" -> "the three sources cited here... not as an exhaustive survey of the
field" (2.6); "no source identified in this search" -> "no source surveyed here" (2.7).
On first pass, three of these rewrites (2.3, 2.5, and the Zhao and Su sentence in 2.6) dropped
the hedge along with the process-narration, turning an honest "we didn't find/couldn't confirm"
into an unhedged claim about what exists in the world. Corrected on review: "we are aware of
no..." and "to our knowledge, no..." restore the epistemic hedge without narrating the search
process, and "is unclear from the published description" (rather than "is not established in
the published description") avoids asserting a fact about Zhao and Su's paper that was never
actually verified, only the honest "nobody has confirmed this" is intended.

**14. "Clusters B, C, or D" (Section 2.7).**
The chapter's actual subsections are numbered 2.3-2.6, not lettered. Replaced with "the
Merkle-audit-log cluster of Section 2.4, the privacy-budget cluster of Section 2.5, or the
threshold-cryptography cluster of Section 2.6."

**15. "the design record for this project" as citable authority.**
Searched Chapters 1, 2, and 4 for this phrase and for "design specification" used as an
appeal to an uncitable internal document. Neither appears anywhere in these three chapters;
the phrase exists only in Chapter 3 (Section 3.3, "As the design record for this project puts
it..."), which is outside this round's edit scope (already corrected in round 1 and not listed
among the files to edit). No change made in Chapters 1/2/4; noting this here so the gap is not
silently missed.

## Chapter 4 (Methodology)

**16. Melara/Hu citation in E1 (Section 4.1).**
literature-search-report.md's own section mapping (line 243) recommends Crosby and Wallach
(2009), Melara et al. (2015), and Hu et al. (2021) specifically for E3's proof-size/
verification-time methodology, not E1's signature-verification-cost methodology. Confirmed
Melara/Hu were already cited in Section 4.3 (E3). Removed the Melara/Hu sentence from Section
4.1 (E1) entirely rather than duplicating a citation that belongs to a different measurement.

**17. G1 subsection framing (Section 4.5).**
Renamed the subsection "G1: Quorum Availability Under the Built Policy and the Veto Policy
SIDS 2026 Specified" (matching Chapter 5 Section 5.5's heading pattern). Rewrote the opening
paragraph to quote SIDS 2026's own veto specification directly (matching Chapter 3 Section
3.4's quotation) and frame G1 as measuring the cost of enforcing that specified guarantee,
not comparing against "a stricter, more intuitive design." Reframed Arm A as "the built
system's own veto-blind policy" and Arm B as adding "exactly the check SIDS 2026 specified and
the built system omits," replacing "a hypothetical" framing throughout. Confirmed the
per-arm statistics paragraph already promises the vetoed-rate/starved-rate decomposition
Chapter 5 Section 5.5 reports, and added a clause making explicit that this decomposition is
the point of collecting those three mutually exclusive rates. Also fixed the identical
"hypothetical stricter" phrasing found in Chapter 2 Section 2.6's CALYPSO discussion, since it
described the same G1 experiment.

**18. Bare "Phase 5" reference (Section 4.2).**
Chapter 3 Section 3.7 names phases descriptively (skeleton phase, consent-core phase, policy
and steward-quorum phase, audit-chain phase, device-fleet phase, interface phase) rather than
numerically. Confirmed the revocation-under-partition mechanism described in Chapter 4's "Phase
5" reference is the device-fleet phase per Chapter 3 Section 3.7's own account. Replaced "Phase
5 of the reference implementation" with "The device-fleet phase of the reference
implementation." (The "C1" label in Section 4's opening paragraph was checked and found already
spelled out on first use, "a two-language conformance-vector suite, C1," so no change was
needed there.)

## Out-of-scope issues found in Chapter 3 (flagged, not fixed)

While cross-checking Chapters 1, 2, and 4 against the already-corrected Chapter 3, two of the
same factual errors fixed in Chapter 2 this round were also found, verbatim or near-verbatim,
in Chapter 3, which is outside this round's edit scope:

- **Mironov/moments-accountant.** Chapter 3 Section 3.5 states Rényi differential privacy is
  "a relaxed definition admitting substantially tighter composition than the earlier
  moments-accountant approach," the same backwards framing fixed in Chapter 2 Section 2.5 (see
  fix 7 above).
- **Certificate Transparency browser enforcement.** Chapter 3 Section 3.6 states Certificate
  Transparency has run "at internet scale since publication, with every major browser now
  requiring it," the same unqualified, undated claim softened in Chapter 2 Section 2.4 (see fix
  9 above); it also does not note RFC 6962's supersession by RFC 9162.

Chapters 2 and 3 now disagree on both points. Neither was in this round's fix list and Chapter 3
was not touched, per the task's file scope, but both should be corrected in Chapter 3 in a
future pass so the manuscript is internally consistent.

## Sources checked for every fix

- Chapter 3 (`03-system-architecture.md`) and Chapter 5 (`05-results.md`), read in full, for
  the corrected steward-quorum veto and G1 framing.
- `references.bib` for the `gaiax` entry's actual title and the `katrakazas2026` entry.
- `[EDIT_THIS]conference_101719.tex` in `~/Downloads/SIDS_2026/`, Table I
  (`\label{tab:comparison}`), for the true per-comparator privacy-mechanism values.
- `literature-search-report.md` for the Rogers/Abowd framing, the Melara/Hu-to-E3 mapping, the
  Schneier and Kelsey mechanism description, and the audit-log-cluster scale-sweep claim.
- WebSearch for RFC 6962/RFC 9162 supersession and current browser CT-enforcement status.
