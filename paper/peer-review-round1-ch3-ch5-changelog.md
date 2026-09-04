# Peer-review round 1 changelog: Chapter 3 (System Architecture) and Chapter 5 (Results)

Each fix below was verified against a real source before writing: the predecessor paper's `.tex` file, the committed experiment CSVs, or the Java/TypeScript source in the repository. Sources checked are named in each entry.

## FIX 1: Undisclosed veto gap in section 3.4 (Steward-Quorum Release Gate)

**Sources checked:** `/home/und3rd06012/Downloads/SIDS_2026/[EDIT_THIS]conference_101719.tex` line 152 (Backend Services subsection, "Quorum and veto" sentence); `connector/extensions/benefits-policy/src/main/java/org/benefits/policy/StewardQuorumFunction.java` (`evaluate` method, lines 46-49); `connector/extensions/benefits-policy/src/main/java/org/benefits/policy/StewardAuthorization.java`.

Confirmed the predecessor paper's exact sentence: "for each release, two of three rotating stewards must co-sign an authorization token; any single steward may veto, in which case the release request is returned to the requester with a written justification." Confirmed `StewardQuorumFunction.evaluate` reads only `authorization.distinctStewardCount() >= required` and never calls `.vetoes()`.

Added a new paragraph to section 3.4 (after the existing signature-count-threshold disclosure) stating this as a second, more serious gap:

> "SIDS 2026's own text specifies a veto, not merely a quorum, as part of the steward gate: 'for each release, two of three rotating stewards must co-sign an authorization token; any single steward may veto, in which case the release request is returned to the requester with a written justification' (Katrakazas and Ntib, 2026). The built system does not enforce this. `StewardQuorumFunction.evaluate` reads only `authorization.distinctStewardCount() >= required`; it never reads `authorization.vetoes()`, confirmed directly in the source. ... It is the predecessor paper's own specified policy, published before this system was built, and the built system silently does not enforce it. Unlike the cryptographic-envelope gap, which diverges from an internal design intent that a reference implementation stopped short of realizing, this gap diverges from a guarantee SIDS 2026 explicitly promised its readers, which is why it is treated here as the more serious of the two."

Corrected the sentence in the CALYPSO/MedCo comparison paragraph that previously implied veto records were functional:

> Old: "What this implementation does hold in common with them is the auditable, quorum-of-independent-parties structure around the release decision, veto, review, and appeal records alongside the signatures, and the framing that a single actor should never unilaterally control disclosure."
>
> New: "...signature, review, veto, and appeal records are all held alongside one another in `StewardAuthorization`, and the framing that a single actor should never unilaterally control disclosure. What it does not yet hold is CALYPSO's and MedCo's cryptographic independence from the operator of the software making the check, and, per the veto gap stated above, the recorded vetoes it does hold are not yet consulted by the release decision itself; a veto is recorded, not enforced."

Also updated section 3.1's opening disclosure and section 3.7's closing summary to name all three corrections (cryptographic envelope, unenforced veto, audit-coverage boundary) instead of two, so the chapter's framing stays consistent throughout.

## FIX 2: Query-time consent-scoping gap, added to end of section 3.3

**Source checked:** `docs/superpowers/specs/2026-09-03-g1-g2-experiments-design.md` section 4.1 ("Today, consent gates only whether a `ContributionRecord` document can be written to CouchDB... Nothing gates whether an already-written contribution is included in a query result... This is a real, confirmed gap, not an assumption.").

Added a new paragraph at the end of section 3.3:

> "Both of the enforcement points just described, the S1 evaluator at the device boundary and the S2 constraint functions at the connector boundary, gate writes... Neither, as originally built, gated whether an already-written contribution could be included in a query-time aggregate: `Contribution`, the record type `benefits-privacy`'s `PrivacyPipeline` and `KAnonymity` actually operate on, carried no consent information at all... This gap went unaddressed until the G2 experiment (Chapter 5, Chapter 6) required closing it; doing so meant building query-time consent scoping into `PrivacyPipeline` itself... so this is a real, additive production capability this project added, not a test fixture confined to G2."

## FIX 3: How revocation-then-resync avoids leaking a revoked record, added to section 3.7

**Sources checked:** `packages/device-sim/src/device.ts` (`Device.revoke`, lines 26-47), `packages/consent-couchdb/src/build-design-doc.ts` (the `validate_doc_update` template), `packages/consent-core/src/evaluate.ts` (`evaluateRecord`/`hasGrant`), `experiments/harness/e2/src/trial.ts` (the `grant()` helper that embeds a fresh, always-valid consent snapshot into each contribution record at write time).

Traced the actual mechanism: `Device.revoke` mutates the device's local grant array and, in the same call, removes (`db.remove`) any not-yet-synced record matching the revoked field/purpose/recipient class from the device's own PouchDB queue, before it is ever replicated. Confirmed the CouchDB `validate_doc_update` function only evaluates a record's own embedded `consent.grants` snapshot (fixed at write time by the device) against the live server clock; it has no access to the device's current, possibly-since-revoked grant state, so it cannot independently catch a queued record the device failed to purge. This means E2's zero-leaked-record result verifies the device-side purge, not a server-side backstop.

Added to section 3.7's device-fleet phase description:

> "That fleet's revocation mechanism is a client-side purge, not a server-side re-check: `Device.revoke` mutates the device's own local grant state and, in the same call, removes any not-yet-synced contribution record matching the revoked field, purpose, and recipient class from the device's local queue, before that record is ever replicated to CouchDB. The CouchDB `validate_doc_update` function does not provide an independent second check of this... E2's zero-leaked-record result (section 5.2) therefore verifies that the device-side purge works on every tested duty cycle, not that the server independently catches what the device failed to withhold."

## FIX 4: E1 signature arm scoping, section 5.1

**Source checked:** `experiments/harness/e1/src/build-signature-design-doc.ts` (a separate design doc E1 builds and installs itself, distinct from `packages/consent-couchdb`'s production `build-design-doc.ts`); grep confirmed no signature/verify logic anywhere in `packages/consent-couchdb` or `packages/consent-core`.

Added:

> "The signature arm exists only inside E1's harness: it runs against a separate CouchDB design document (`build-signature-design-doc.ts`) that E1 builds and installs itself, never against the production `consent-couchdb` design document any real deployment's provisioning path installs. The production S1 filter performs no per-record signature verification of any kind; the three arms above measure what per-record signature verification would cost if it were added to the write path, not a cost the deployed system currently pays."

## FIX 5: G1 central reframe, section 5.5

**Source checked:** `docs/superpowers/specs/2026-09-03-g1-g2-experiments-design.md` section 3.1 (Arm A = real `StewardQuorumFunction`, quorum-only; Arm B = harness-only `HypotheticalVetoBlocksQuorumFunction`, same swept quorum count plus a permanent veto block); `experiments/results/g1/availability.csv` filtered to `veto_share=0.3, N=20`.

Renamed the section heading from "G1: Quorum Availability Under the Real and a Hypothetical Veto Policy" to "G1: Quorum Availability Under the Built Policy and the Veto Policy SIDS 2026 Specified." Rewrote the opening paragraph to describe Arm A as the built system's veto-blind policy and Arm B as adding exactly the veto check SIDS 2026 specified, on the same swept quorum threshold Arm A uses (not a fixed rule), noting SIDS 2026 names two-of-three co-signature as its specific instance. Added the explicit reframe:

> "Framed this way, G1 does not compare the built system against an arbitrary stricter alternative; it measures the availability cost of enforcing the veto guarantee SIDS 2026 actually specified in its own text, a guarantee the implementation currently does not provide."

Computed the vetoed-rate/starved-rate split at `veto_share=0.3, N=20` directly from the CSV: `arm_b_starved_rate` is 0.0 at all 15 plotted points; `arm_b_vetoed_rate` alone accounts for the entire reported failure rate (0.910 to 1.0 across the slice). Added a new paragraph reporting this split and its implication:

> "Splitting Arm B's failure rate into its two mutually exclusive components at this same slice shows that the gap is driven almost entirely by vetoes rather than by genuine starvation. At all 15 plotted points, `arm_b_starved_rate` is 0.0... This means Arm B's high failure rate is not evidence that the veto-enforcing policy is prone to stalling independently of stewards' objections; it is a direct, mechanical consequence of a 0.3 veto share applied across a pool of 20 stewards... The veto mechanism, when actually enforced, works as specified: it blocks release whenever a steward objects. What the data show is the availability price of that guarantee at this veto share, not a separate defect in the quorum-reaching logic itself. Arm A, by contrast, does exhibit genuine starvation at the higher quorum fractions independent of any veto... `arm_a_starved_rate` reaches 0.435 at quorum_fraction = 0.7, p_act = 0.1."

## FIX 6: Real IQR figures added to E1, E4, G1, G2

**Sources checked:** `experiments/results/e1/{baseline,structural,signature}.csv` (raw per-trial data, IQR computed via linear interpolation, matching the method in `experiments/analysis/e1_figure.py`'s `grouped.quantile(0.25/0.75)` and `experiments/harness/e4/src/main/java/org/benefits/experiments/e4/Statistics.java`'s `percentile` method); `experiments/results/e4/utility.csv`'s `surviving_iqr_relative_error` column; `experiments/results/g1/availability.csv`'s `arm_a_iqr_ticks_to_quorum`/`arm_b_iqr_ticks_to_quorum` columns; `experiments/results/g2/heterogeneity.csv`'s `surviving_coverage_iqr_relative_error`/`surviving_exclusion_iqr_relative_error` columns.

- **5.1 (E1):** added IQR at field_count=1, batch=10 (baseline 2.09 ms, structural 2.07 ms, signature 97.02 ms) and batch=10,000 (baseline 2,938.21 ms, structural 7,651.16 ms, signature 117,291.37 ms).
- **5.4 (E4):** added IQR at population=30/300, epsilon=0.1: k=10 IQR 0.502 (pop 30) / 0.579 (pop 300); k=5 IQR 1.140 (pop 30); k=3 IQR 1.223 (pop 300).
- **5.5 (G1):** added Arm A median-ticks-to-quorum/IQR ranges per quorum-fraction panel (e.g. IQR up to 10.0 at quorum_fraction=0.7, p_act=0.1).
- **5.6 (G2):** added surviving coverage/exclusion IQR at epsilon=1.0 (0.023 at h=0.0 to 0.142 at h=1.0 for exclusion; 0.023 to 0.105 for coverage) and epsilon=0.25 (coverage IQR range 0.243-0.569, exclusion IQR range 0.193-0.550).

## FIX 7: Corrected exclusion-error definition, section 5.6

**Source checked:** `experiments/harness/g2/src/main/java/org/benefits/experiments/g2/HeterogeneitySweepPointRunner.java` lines 63-134, confirming `coverageError = |observed - consentingCount| / consentingCount` and `exclusionError = |observed - fullCount| / fullCount`, both computed from the same noised `pipeline.compute()` output (`observed`/`observedCount`/`observedProportion`), not from separate pre-noise and post-noise measurements.

Replaced the incorrect claim that exclusion error was measured "before any privacy noise is applied" with a corrected definition:

> "Both statistics are computed from the same noised `PrivacyPipeline.compute()` output; they differ only in which ground truth that noised output is compared against. Surviving coverage error compares the observed, noised result against the consenting population's own true count... this statistic isolates the DP mechanism's own noise... Surviving exclusion error compares the same observed, noised result against the full generated population's true count, unfiltered by consent: this statistic captures total distortion against the true community value, noise and exclusion bias together, not exclusion bias in isolation and not a quantity computed before noise is applied. It is the gap between exclusion error and coverage error, not exclusion error alone, that isolates the effect of heterogeneous consent specifically..."

Added a closing paragraph quantifying that gap at epsilon=1.0 (widening from ~0.0 at h=0.0 to ~0.326 at h=1.0, i.e. 0.348 exclusion against 0.022 coverage) and stating the more precise version of the finding: "heterogeneous consent introduces a distortion on top of DP noise, one that grows with h, while the DP mechanism's own noise contribution, measured by coverage error, does not."

## FIX 8: Survivorship-bias disclosure, section 5.4

**Source checked:** `experiments/results/e4/utility.csv`, filtered to population=30, epsilon=0.1, uniform skew: `surviving_data_points` is 24 (k=3), 13 (k=5), 10 (k=7), and 3 (k=10) out of 68 total data points; `suppression_refusal_rate` is 0.956 at k=10.

Added a new paragraph after the existing suppression-rate reporting:

> "The population = 30, epsilon = 0.1 numbers just reported carry sharply unequal statistical weight across k, since the surviving-only statistic is computed only over the queries that were not suppressed or refused at that cell: the 0.513 figure for k = 10 is a median over 3 surviving data points out of 68, the k = 7 figure over 10, the k = 5 figure over 13, and the k = 3 figure over 24. A reader comparing 0.513 (k = 10) against 1.127 (k = 5) at this cell is comparing a statistic computed on 3 points against one computed on 13, not two equally-supported estimates... The same caution applies wherever a low-epsilon, small-population, high-k cell is quoted elsewhere in this section or in Chapter 6."

## Verification notes

- Grepped both files for em-dashes (`-`) and double hyphens (`--`): none found.
- Confirmed `katrakazas2026` resolves in `references.bib` and matches the existing `(Katrakazas and Ntib, 2026)` citation format used throughout both chapters.
- All IQR figures in FIX 6 were recomputed to match the linear-interpolation (NumPy/pandas default, R type 7) convention used by the project's own `Statistics.iqr`/`.quantile()` code, not an alternative (e.g. Tukey-hinge) method, so they are consistent with how the committed CSV IQR columns and figure scripts compute the same statistic.
- FIX 2's "real production capability" claim was confirmed directly against the Java source, not only the design spec: `consentCovers`/`consentGrants` appear in `connector/extensions/benefits-privacy/src/main/java/org/benefits/policy/Contribution.java` and `PrivacyPipeline.java`.
