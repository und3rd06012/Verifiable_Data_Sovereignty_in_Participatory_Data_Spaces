# BENEFITS MDPI Systems Extension — Chapter Plan

**Produced by:** academic-paper skill, plan mode, 2026-09-03.
**Base paper:** SIDS 2026 (`/home/und3rd06012/Downloads/SIDS_2026/[EDIT_THIS]conference_101719.tex`), "Verifiable Data Sovereignty for Vulnerable Populations in Participatory Data Spaces," IEEE conference format, EU Horizon Europe GA no. 101179032.
**Target venue:** MDPI Systems (extension article, no page limit).
**Structure:** IMRaD, expanded with a dedicated System/Implementation chapter and a standalone Future Work chapter.

## Thesis (locked)

We close the implementation gap SIDS 2026 left open by building the full BENEFITS reference architecture, and we subject each of its sovereignty mechanisms to direct empirical measurement, quantifying, for the first time, what these architectural guarantees actually cost and where they degrade, giving the sovereignty claim an objective, mechanism-level line of evidence that complements SIDS 2026's subjective trust findings.

Thesis type: evaluative / mixed (architectural contribution + empirical characterization).
Scope: the built reference implementation and its six mechanism-level experiments; explicitly not a field/pilot evaluation (that is future work, the SIDS-2026-announced three-country pilot).

## Contribution claim

Two lines of evidence for one sovereignty claim, held as complementary, not substitutive:
1. SIDS 2026's subjective evidence: participants trust the *idea* of observable sovereignty controls (n=23, self-report trust scores).
2. This paper's objective evidence: the built mechanisms verifiably deliver what they claim, characterized under real mechanism-level stress (partition, tamper, quorum unavailability, privacy-utility tradeoff, consent heterogeneity).
Ruling (stress-test resolution): the Discussion must NOT claim the mechanism results validate or explain the n=23 trust score. They are two separate legs of one argument.

## Argument stress test (Step 3)

Challenge raised: a skeptical reviewer could say the mechanism-level results are a different kind of evidence that doesn't validate the *same* trust claim SIDS 2026 made.
Resolution (ruling, since the user did not have a settled answer and asked me to decide): frame explicitly as complementary evidence lines in the Discussion, not as retroactive validation of the trust score. This is the lower-risk, non-overclaiming framing, consistent with the honest treatment of E1's no-crossover finding and G1's twice-corrected result elsewhere in this project.

## Chapters

### 1. Introduction
Core argument: SIDS 2026 proposed architecture-not-procedure as the answer to unverifiable sovereignty for vulnerable populations, but its own evaluation was necessarily qualitative because the mechanisms were still specification, not code (its own "Technical Implementation Status" section says so explicitly). This paper closes that gap.
Evidence: SIDS 2026's own admission that the steward portal and DP mechanisms were "in the design and specification phase pending pilot deployment"; the concrete open questions the eventual implementation had to settle (Ed25519-in-CouchDB feasibility, epoch length, revocation-under-partition guarantees, whether the meet operation makes realistic aggregates too thin to be useful).

### 2. Related Work
Core argument: extends SIDS 2026's own related-work section and comparison table one layer down, from "governance models for vulnerable populations" to "systems that provide the same properties they claim, and whether those properties are verified."
Evidence: SIDS 2026's Table 1 positioning table (IDSA RAM, Gaia-X, DECODE, SPPEAR, Open Humans, MyData, Chereja et al., McInnis et al.) as the seed; extend with technical neighbors on EDC-based connector implementations, Merkle-audit-log systems, k-anonymity/DP budget-accounting systems.
Note: drafted initially against SIDS 2026's existing `references.bib`; a real systematic literature search (Phase 1 of this pipeline) runs before this chapter is submission-ready.

### 3. System Architecture and Implementation
Core argument: the architecture SIDS 2026 sketched is now a real, running system.
Evidence: EDC connector (pinned 0.18.0) with `benefits-policy`, `benefits-privacy`, `benefits-audit` extensions; TypeScript `consent-core`/`consent-odrl`/`consent-couchdb` packages; a two-language conformance-vector suite (C1) proving edge (CouchDB validation function, S1) and connector (S2) enforcement agree; the phase 0-6 gates (real two-connector negotiation refusing without quorum, real CouchDB rejecting unconsented writes, participant-web/steward-portal built and click-tested).

CORRECTION (found during Chapter 3 drafting, verified directly against `connector/extensions/benefits-policy/src/main/java/org/benefits/policy/{StewardAuthorization,StewardQuorumFunction}.java`): the steward-quorum release gate is NOT implemented as Shamir secret sharing or any cryptographic threshold-decryption envelope, despite SIDS 2026's own description ("aggregated outputs are wrapped in an envelope keyed by the steward quorum...") and an earlier design intent to retain that as a second lock on release. The built mechanism is a signature-count quorum check (`StewardQuorumFunction.evaluate`: `signed >= required`, reading `distinctStewardCount()` against an ODRL constraint threshold) enforced as a policy-engine check at contract negotiation, not a cryptographic guarantee independent of who operates the connector. This is a real, load-bearing finding for this paper's own thesis (measuring what was actually built, not what was claimed) and MUST be stated honestly in the System Architecture chapter (already done, section 3.4) and again in the Discussion chapter's Limitations, not smoothed over. Any chapter mentioning "Shamir secret sharing" for the steward-quorum mechanism is describing the wrong thing and must be corrected.

### 4. Experimental Methodology
Core argument: six mechanism-level experiments, each targeting one of the open questions the architecture left unsettled at design time, each with a seeded, reproducible harness and committed CSV output.
Evidence:
- E1: signature-verification cost, does the pure-JS Ed25519-in-CouchDB approach scale.
- E2: revocation under network partition, zero-leaked-record guarantee.
- E3: tamper detection at the audit chain, proof-size/verification-time cost vs. epoch size.
- E4: privacy-budget-vs-utility tradeoff.
- G1: quorum availability under the real veto policy vs. a hypothetical stricter one.
- G2: consent heterogeneity vs. aggregate utility (settles whether the meet operation makes realistic aggregates too thin to be useful).

### 5. Results
Core argument: report only, organized one subsection per experiment, around the six already-built figures (`experiments/analysis/figures/`).
Evidence, stated honestly including non-obvious findings:
- E1: signature verification is costliest at every swept point, no crossover; dominates from the smallest batch onward. Must NOT be framed as "the point where signature cost overtakes structural cost" (an overclaim already caught and corrected during this session's figure-generation review).
- E2: revocation propagates and reaches consistency within bounded ticks even at low connectivity; zero leaked records across the swept grid.
- E3: proof size and inclusion-verification time grow with epoch size; tamper detection at 100% at the mechanism level.
- E4: accuracy-vs-epsilon tradeoff curves, using the surviving-statistic columns specifically (the plain columns are pinned at a sentinel value across most of the swept grid, a defect this session's own earlier fix waves solved).
- G1: the real veto policy has substantially lower quorum failure than a hypothetical stricter one (minimum divergence 0.555 across all measured points at veto_share=0.3, N=20) — validates the current design's availability/accountability tradeoff. This exact finding required two real design corrections during this session's figure-generation work (wrong filter degenerating both arms to identical zero, then wrong plotted quantity excluding Arm B's dominant failure mode) before it was measured correctly.
- G2: exclusion bias from heterogeneous consent grows with heterogeneity while ordinary DP noise does not.

### 6. Discussion
Core argument: mechanism-level verification changes what "sovereignty" can mean in an architecture like this, from "trust the design" to "here is exactly what it guarantees, at what cost, and where it breaks."
Evidence: dialogue with SIDS 2026's own "Open Governance Challenges" section (privacy budget vs. community utility, now has real numbers instead of a stated concern; sustainable steward governance, quorum availability findings inform the veto-policy tradeoff directly).
Complementary-evidence framing (see Contribution claim above) belongs here explicitly.
Limitations to own honestly: no live pilot yet; simulation-based experiments, not field measurements; E1's lack of a crossover corrects rather than confirms an intuitive assumption.

### 7. Conclusion
Core argument: sovereignty-as-architecture is not just buildable but measurable, and the measurements mostly, not uniformly, support the design.
Evidence: restates the thesis and what the six experiments collectively establish.

### 8. Future Work
Core argument: this paper is the technical readiness gate for the three-country pilot SIDS 2026 already announced.
Evidence: whether the mechanism-level guarantees measured here hold under real field conditions, not simulation; the adaptive-privacy-budget idea SIDS 2026 raised, still unresolved; sustainable steward governance models, still to be compared empirically across sites (London, Tuscany, Zaragoza); whether community-governed participatory data measurably changes service decisions, SIDS 2026's own stated pilot question.

## Word count

No page limit (MDPI Systems). Chapters sized by content need, not a fixed target.

## Open items carried forward

1. Related Work needs a real systematic literature search (this pipeline's Phase 1) before submission.
2. Exact figures/numbers throughout Results must be pulled from the real committed CSVs in `experiments/results/` and `experiments/analysis/figures/`, not approximated from memory.

## Peer review round 1 (2026-09-03)

Two independent adversarial reviews (rigor/evidence; argument/writing quality) ran against the full assembled draft. Findings triaged with an advisor consult; fix plan executed in 3 sequenced dispatches (Ch3+Ch5 source-of-truth first, then Ch1/2/4 and Ch6/7/8+abstract in parallel reading the corrected Ch3/Ch5), followed by a scoped re-review (loop 2 of 2) and a final small mechanical fix pass. All fixes verified against real sources (the SIDS 2026 .tex, the real CSVs, the Java/TypeScript source, references.bib), not assumed.

**The central ruling, more significant than anything else in this round:** the original G1 story was wrong. SIDS 2026 explicitly specifies "any single steward may veto, in which case the release request is returned to the requester with a written justification." The built system's production `StewardQuorumFunction` never reads recorded vetoes at all (`signed >= required` only). G1's "Arm B" is not a hypothetical stricter policy invented for comparison, it is SIDS 2026's own published, specified policy; "Arm A" is the built system that silently omits it. G1 therefore measures the availability cost of actually enforcing a guarantee the predecessor paper already promised and the implementation currently does not provide, not a validated design choice. Decomposing Arm B's failure rate shows it is driven almost entirely by vetoes firing as designed (starved rate 0.0 at every plotted point in the reported slice), not by genuine quorum starvation. This reframe was propagated across the Abstract, Introduction, System Architecture, Methodology, Results, Discussion, Conclusion, and Future Work, replacing every instance of the old "hypothetical stricter alternative... governance choice left open... set correctly for availability" framing.

Other real, load-bearing findings fixed: the steward-quorum release gate has TWO separate, honestly disclosed gaps (not one), the previously-known signature-count-vs-cryptographic-envelope gap, and this round's newly surfaced veto-checking omission (ranked more serious, since it diverges from what the published predecessor specified, not just an internal design intent); E1's signature-verification arm is harness-only and never runs in the production S1 filter, now disclosed everywhere the finding is cited; E3 does not itself establish tamper detection (that was proven separately by an earlier phase's unit tests; E3 measures cost only), a claim that had drifted into an overclaim ("E3 confirms perfect tamper detection") in the Conclusion; "zero leaked records" was overstated as an absolute guarantee rather than an observed simulation result; G2's exclusion-error statistic was mis-defined in two of three places it appeared (it is noise-plus-exclusion-bias against the full population, not exclusion-alone "before any privacy noise is applied"; confirmed directly against the G2 harness source); a real novelty overclaim ("for the first time... quantifying what these guarantees cost") was softened to the defensible combination-claim Related Work's own honest gap-reporting actually supports; a factual mischaracterization of SIDS 2026's own comparison table (claiming all comparators used policy/certificate/UX-based mechanisms when several use concrete techniques) was corrected; a false claim that this manuscript's own System Architecture chapter contains connector-level performance measurements (it does not; connector benchmarking was explicitly out of scope per this project's own design spec) was removed; several literature mischaracterizations (Mironov/RDP's actual relationship to the moments accountant, Schneier and Kelsey's hash-chain-vs-forward-secure-MAC split, an out-of-date RFC 6962 claim now noting its RFC 9162 supersession, a wrong Gaia-X document title) were corrected in every chapter they appeared in; meta-commentary that narrated the literature-search process into the manuscript's own voice ("this search," "artificially thinned to fit a target range," unresolved lettered "Clusters B/C/D") was rewritten into ordinary scholarly prose; three real limitations were restored/added to Discussion's Limitations section (the audit-chain coverage boundary, the query-time consent-scoping gap G2 required building, and the mock-identity-module limitation); IQR/dispersion statistics were added to Results for E1/E4/G1/G2 to match what Methodology already promised; E4's survivorship bias at thin-sample cells (as few as 0-3 of 68 surviving points) was disclosed everywhere a thin-cell number is quoted; a bibliography that would not compile (duplicate `new-references.bib`, a malformed entry boundary, a missing self-citation entry) was fixed.

Final re-review verdict: Minor Revision, six small remaining mechanical items (all one-to-two sentence fixes), all applied directly. Per this project's two-loop revision policy, remaining stylistic/pre-existing items (a few internal-document appeals still present outside the chapters explicitly in scope, minor phrasing nits) are carried as Acknowledged Limitations rather than triggering a third loop.
