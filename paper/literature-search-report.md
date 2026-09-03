# Literature Search Report

Prepared for: BENEFITS MDPI Systems extension article, Related Work chapter (Phase 1, systematic literature search).
Extends: SIDS 2026 (`[EDIT_THIS]conference_101719.tex`) Related Work section and `references.bib`, which already covers the IDSA Reference Architecture Model, the Gaia-X Trust Framework, the EU Data Governance Act / Data Act, DECODE, SPPEAR, participatory sensing, community-based participatory research, Open Humans, MyData, and informed-consent-design-in-digital-health work. This report does not repeat that ground; it searches four new clusters made necessary by this paper's shift from a proposed architecture to a built, measured system.
Last searched: 2026-09-03.

## Search Strategy

**Databases and engines used:** Crossref (`api.crossref.org`, used for direct DOI-metadata verification), dblp, arXiv, IACR ePrint, Semantic Scholar, IEEE Xplore, ACM Digital Library, USENIX proceedings, VLDB proceedings, MDPI, ScienceDirect, and general web search, cross-checked against each other so that no source rests on a single unverified source.

**Verification method:** every candidate was screened by (1) a targeted search pass per cluster, (2) fetching the source's own landing page, arXiv abstract page, or dblp/USENIX/IACR record to confirm exact title, full author list, venue, and year, and (3) for every source carrying a DOI, an independent second-pass resolution of that DOI against the Crossref API (`https://api.crossref.org/works/<doi>`) performed separately from the initial cluster search, comparing the returned title/author/container-title/year against the reported record. No source was included unless it passed both passes. Sources found only via a search-engine snippet, with no landing page or registry record reachable, were dropped rather than included on inference.

**Search strings per cluster:**
- Cluster A (EDC / Data Space connector implementations with technical evaluation): "Eclipse Dataspace Components evaluation", "Eclipse Dataspace Components performance benchmark", "IDS Trusted Connector Fraunhofer AISEC evaluation", "Catena-X connector performance", "data space connector comparison survey", "Gaia-X reference connector implementation evaluation".
- Cluster B (Merkle-tree / hash-chain tamper-evident logging with empirical proof-size or tamper-detection evaluation): "Schneier Kelsey secure audit logs tamper", "Crosby Wallach efficient data structures tamper-evident logging USENIX Security 2009", "Certificate Transparency RFC 6962 Laurie", "CONIKS Melara USENIX Security 2015 key transparency", "Merkle^2 Hu IEEE S&P 2021 proof size", "verifiable log-backed map", "transparency log proof size verification time evaluation".
- Cluster C (k-anonymity / differential-privacy systems with an explicit budget accountant and empirical utility measurement): "Abadi deep learning differential privacy moments accountant CCS 2016", "Mironov Renyi differential privacy CSF 2017", "Hay DPBench differential privacy empirical evaluation SIGMOD 2016", "Rogers LinkedIn differential privacy audience engagement API 2020 privacy budget production", "Abowd 2020 Census TopDown algorithm differential privacy", "Prasser ARX k-anonymity tool software practice experience", "differential privacy budget accountant empirical utility accuracy tradeoff".
- Cluster D (threshold cryptography / quorum authorization for data governance, access control, or consent, with an availability-vs-security tradeoff): "Vanish self-destructing data Geambasu Kohno Levy Zahorjan USENIX Security 2009", "Defeating Vanish Wolchok Hofmann Heninger Felten Halderman NDSS 2010", "CALYPSO Kokoris-Kogias threshold access control auditable VLDB 2021", "MedCo Raisaro secure medical data sharing threshold decryption", "Drynx Froelicher privacy-preserving distributed statistical computation collective encryption", "Shamir secret sharing consent management access control data governance evaluation", "threshold cryptography quorum data release availability tradeoff".

**Date range:** no lower bound (several foundational papers, e.g. Schneier and Kelsey 1999, are expected and included); upper bound through 2026-09-03.

**Supplementary MDPI Systems search (added mid-task):** after the four core clusters were screened, a further search was run specifically against the paper's own target venue (MDPI Systems, ISSN 2079-8954), using the Crossref API's journal-scoped works endpoint (`https://api.crossref.org/journals/2079-8954/works?query=...`) with queries covering data-space/data-sovereignty, differential privacy/audit-log/consent/threshold-cryptography terms, blockchain/Shamir/quorum access-control terms, and participatory data-governance terms. This surfaced two relevant, verified sources (Norikane and Nishimura 2025; Albalwy 2026), reported below and folded into the totals throughout this document.

## Screening Results

| Cluster | Initial hits scanned | After title/abstract screening | Final included |
|---|---|---|---|
| A (EDC / connector implementations) | ~20 core + ~15 MDPI Systems-scoped | 5 core + 1 from Systems | 4 (3 core + 1 from Systems) |
| B (Merkle / hash-chain audit logs) | ~45 | 12 | 5 |
| C (DP / k-anonymity budget accounting) | ~65 | 8 | 6 |
| D (threshold / quorum data governance) | ~35-40 | 10 | 3 |
| MDPI Systems venue-relevant, not mapped to a core cluster | ~15 (via Crossref journal-scoped queries against ISSN 2079-8954) | 3 | 1 |
| **Total** | **~180** | **~39** | **19** |

A supplementary search was run against the paper's own target venue specifically (Crossref API scoped to Systems, ISSN 2079-8954) after the four core clusters were already screened, per an instruction added mid-task to check for relevant prior art published in MDPI Systems. One source maps cleanly into Cluster A (Norikane and Nishimura 2025, counted there above and in the Literature Matrix below); one (Albalwy 2026) is thematically adjacent to this paper's data-governance/access-control theme but does not map cleanly onto any of the four defined clusters, so it is reported separately below rather than forced into one.

Cluster D landed at 3, the floor of the target range, and is reported honestly rather than padded; see Identified Gaps. Cluster A's core search also landed at 3, but a supplementary MDPI Systems search (added per instruction mid-task) found one further relevant source, bringing Cluster A to 4; see below.

## Annotated Bibliography

### Cluster A: EDC / Data Space Connector Implementations

### Dam, Klausner, Neumaier, and Priebe (2023). A Survey of Dataspace Connector Implementations.
Type: Comparative survey/short paper. Method: structured qualitative comparison of open-source IDS-compliant dataspace connector implementations (architecture, data model, usage-control language support) rather than a performance benchmark.
Key findings: maps the landscape of open-source IDS-compliant dataspace connector implementations against IDS Reference Architecture requirements, identifying which connectors actually implement usage-control enforcement versus only claim to.
Relevance to RQ: establishes that "which connectors are real, running software with verified enforcement" is itself an open comparative question in the literature, the same premise this paper's Chapter 2 extends one layer further (verified technical evaluation, not just feature comparison).
Quality assessment: peer-reviewed conference paper (CEUR-WS proceedings), non-empirical (survey, not measurement), weaker fit than the other two Cluster A sources, included because it is the closest thing to a systematic connector landscape review currently published.
Potential use: Related Work (Cluster A subsection), as the framing citation for "connector implementations exist and vary in what they actually enforce."
Verified via: https://arxiv.org/abs/2309.11282 (arXiv preprint, title/author list confirmed) and https://ceur-ws.org/Vol-3606/paper41.pdf (published version, ITADATA 2023, CEUR Workshop Proceedings Vol-3606, paper 41, ISSN 1613-0073); dblp record https://dblp.org/rec/conf/itadata/DamKNP23.html independently confirms venue and authors.

### Mügge, Grosse Erdmann, Riedelsheimer, Manoury, Smolka, Wichmann, and Lindow (2023). Empowering End-of-Life Vehicle Decision Making with Cross-Company Data Exchange and Data Sovereignty via Catena-X.
Type: Applied systems paper with a real use case. Method: implements a Catena-X (EDC-based) cross-company data-exchange pipeline for end-of-life-vehicle decision support and validates it against an actual cross-company data flow between named industrial roles.
Key findings: demonstrates that an EDC-based connector deployment can carry real, sovereignty-respecting cross-company data exchange for a concrete downstream decision-support use case, not just a synthetic demo.
Relevance to RQ: the strongest Cluster A match for "a real EDC-based deployment with a reported technical evaluation," directly supporting the claim that this paper's own EDC 0.18.0 deployment sits in an active, if thin, empirical literature.
Quality assessment: peer-reviewed, MDPI Sustainability, DOI-verified. Real use-case validation but not a rigorous latency/throughput benchmark.
Potential use: Related Work (Cluster A), System Architecture chapter (comparator for what "EDC in production" looks like elsewhere).
DOI: 10.3390/su15097187. Verified via Crossref API (`https://api.crossref.org/works/10.3390/su15097187`): title, all 7 authors, journal, volume 15, issue 9, article no. 7187, year 2023 all matched exactly.

### Kalogeropoulos, Vlontzou, Psaromanolakis, Zarogianni, and Theodorou (2023). EdgeDS: Data Spaces enabled Multi-access Edge Computing.
Type: Systems paper with an open-source prototype and scalability experiments. Method: implements an IDS-connector-as-a-service integrated into an ETSI Multi-access Edge Computing architecture, and runs experiments measuring functionality and scalability of connector-mediated data exchange.
Key findings: reports a working open-source connector-as-a-service prototype with measured scalability behavior under an edge-computing deployment model, one of few Cluster A sources with any quantitative evaluation component.
Relevance to RQ: a comparator for connector deployment models and the kind of scalability evaluation (though at a different layer) this paper's own System Architecture chapter should be positioned against.
Quality assessment: arXiv preprint, no confirmed peer-reviewed venue found during this search; treated as a preprint-quality source, included because it independently verifies (via its own scalability experiments) that connector implementations are beginning to be evaluated empirically, supporting the identified-gap claim below rather than closing it.
Potential use: Related Work (Cluster A), Identified Gaps (supports the claim that rigorous EDC performance evaluation remains thin).
Verified via: https://arxiv.org/abs/2304.05966 (arXiv abstract page, title and 5-author list confirmed, submitted 2023-04-12).

### Norikane and Nishimura (2025). Security and Resilience of a Data Space Based Manufacturing Supply Chain.
Type: Applied systems paper using enterprise architecture modeling and scenario-based simulation, published in the paper's own target venue (MDPI Systems). Method: models a data-space-based manufacturing supply chain using the Unified Architecture Framework and runs scenario-based simulations of physical-security and cybersecurity disruption scenarios, comparing conventional manufacturing recovery against a data-space-enabled flexible-manufacturing alternative.
Key findings: an alternative manufacturing process using flexible methods coordinated through a data-space platform outperforms conventional recovery on parts-production volume and cost under simulated disruption scenarios, presented as a security-control measure for supply-chain risk mitigation.
Relevance to RQ: not a connector performance benchmark (it evaluates supply-chain resilience outcomes via simulation, not the connector's own technical properties), so it is weaker evidence for Cluster A's core claim than Mügge et al. or Kalogeropoulos et al. above. It is included specifically because it is real, verified, technically-evaluated prior work on a built data-space deployment published in this paper's own target journal, directly relevant to demonstrating venue fit.
Quality assessment: peer-reviewed, MDPI Systems, DOI-verified, part of the special issue "Systems Methodology in Sustainable Supply Chain Resilience."
Potential use: Related Work (Cluster A, as a target-venue precedent), Introduction (venue-fit framing).
DOI: 10.3390/systems13080676. Verified via Crossref API (`https://api.crossref.org/works/10.3390/systems13080676`): title, both authors, journal Systems, volume 13, issue 8, article no. 676, year 2025 all matched.

### Cluster B: Merkle-Tree / Hash-Chain Tamper-Evident Logging

### Schneier and Kelsey (1999). Secure Audit Logs to Support Computer Forensics.
Type: Foundational cryptographic mechanism paper. Method: proposes a hash-chain scheme (chained one-way hashes plus forward-secure MACs) making log entries written before a compromise unreadable and undetectably-unmodifiable even to an attacker who later controls the logging machine.
Key findings: establishes the retroactive-tamper-detection guarantee that Merkle-epoch audit chains generalize; the security argument is that any alteration of an already-hashed entry breaks the chain from that point forward.
Relevance to RQ: the direct conceptual ancestor of this paper's own hash-chained audit log (each entry's hash chained to the previous, so retroactive alteration breaks the chain), which is the mechanism SIDS 2026 describes in its architecture section without citing a source for it; this entry supplies that missing citation.
Quality assessment: ACM TISSEC, DOI-verified, foundational and heavily cited (over 25 years old but still the standard reference for chained-hash audit logs).
Potential use: Related Work (Cluster B), System Architecture (the mechanism this paper's audit-chain extends to epoch-batched Merkle trees).
DOI: 10.1145/317087.317089. Verified via Crossref API: title "Secure audit logs to support computer forensics", authors Bruce Schneier and John Kelsey, journal ACM Transactions on Information and System Security, volume 2, issue 2, pages 159-176, year 1999. (Note: this Crossref-confirmed volume/issue, 2/2, corrects an initial 1/3 misattribution surfaced during cluster search; Crossref is treated as authoritative.)

### Crosby and Wallach (2009). Efficient Data Structures for Tamper-Evident Logging.
Type: Systems/algorithms paper with a working implementation. Method: proposes a Merkle-tree-based (history-tree-style) tamper-evident logging structure producing logarithmic-size membership and consistency proofs, improving on prior linear-size hash-chain schemes, and evaluates proof size and computation cost as log size scales.
Key findings: demonstrates empirically that proof size and verification cost scale logarithmically rather than linearly with log size for a tree-structured audit log, and that large log servers can prune old entries while still proving no events were deleted.
Relevance to RQ: the closest direct precedent for this paper's own E3 experiment (proof size and inclusion-verification time growth vs. epoch size for a Merkle-epoch audit chain). Crosby and Wallach is the paper that established proof-size-vs-log-size as the metric of interest for this class of system.
Quality assessment: USENIX Security 2009, no registered Crossref DOI exists for this proceedings entry (USENIX did not assign DOIs to 2009-era papers), cited via its stable ACM DL record instead. Strong, widely cited (peer-reviewed, top-tier security venue).
Potential use: Related Work (Cluster B, anchor citation), Experimental Methodology (E3 framing).
Verified via: multiple independent, mutually corroborating records found in this session's own search results (dblp author record for Scott A. Crosby listing this exact title and USENIX Security 2009 venue; the paper's ACM DL guide entry at https://dl.acm.org/doi/10.5555/1855768.1855788; and USENIX's own presentation page for the paper), all agreeing on title, co-author Dan S. Wallach, venue, and pages 317-334. Direct WebFetch of the dblp, ACM DL, and USENIX pages was blocked by bot protection (403/503 responses) during this verification pass, so this entry rests on converging search-result corroboration across independent registries rather than a single freshly fetched page (the Hu et al. 2021 entry below shares this caveat for its page range specifically, though its title and authors were confirmed by direct fetch).

### Laurie, Langley, and Kasper (2013). Certificate Transparency.
Type: Internet standard (IETF RFC, experimental track). Method: specifies an append-only Merkle-tree transparency log for publicly logging TLS certificate issuance, defining Merkle audit paths (inclusion proofs) and consistency proofs between successive log states.
Key findings: the design has been deployed at Internet scale since publication (all major browsers now require Certificate Transparency for TLS certificates), making it the most widely operational real-world instance of Merkle-tree inclusion/consistency proofs of any source in this cluster.
Relevance to RQ: the canonical deployed precedent for "epoch"-style Merkle tree roots with standardized inclusion-proof and consistency-proof formats, directly parallel to this paper's own Merkle-epoch audit chain design and its measured proof-size/verification-time behavior.
Quality assessment: IETF RFC, no DOI (RFCs are not DOI-registered), verified directly against the RFC Editor's own page.
Potential use: Related Work (Cluster B), System Architecture (design precedent for epoch-based Merkle proofs), Discussion (real-world deployment scale as context for this paper's own mechanism-level measurements).
Verified via: direct fetch of https://www.rfc-editor.org/rfc/rfc6962.html, confirming title, all three authors, and June 2013 publication date verbatim.

### Melara, Blankstein, Bonneau, Felten, and Freedman (2015). CONIKS: Bringing Key Transparency to End Users.
Type: Systems paper with a full implementation and bandwidth measurements. Method: a Merkle-prefix-tree-based key-transparency log letting end users audit their own key bindings without relying on a global monitor, evaluated for bandwidth cost per user and per provider as the user base scales.
Key findings: measured bandwidth cost stays under 20 kB/day per user even for billion-user providers, and a constant ~2.5 kB/provider/day suffices for collective non-equivocation auditing, an explicit proof-size/bandwidth-vs-scale evaluation.
Relevance to RQ: a directly comparable empirical evaluation methodology, measuring proof/audit cost against system scale, that this paper's own E3 proof-size-vs-epoch-size measurement follows the same shape as.
Quality assessment: USENIX Security 2015, peer-reviewed, no registered DOI found (typical for USENIX proceedings of this era); confirmed via dblp and the official USENIX PDF.
Potential use: Related Work (Cluster B), Experimental Methodology (E3 comparator), Discussion.
Verified via: https://dblp.org/rec/conf/uss/MelaraBBFF15.html (title, all 5 authors, USENIX Security 2015, pp. 383-398 confirmed) and https://www.usenix.org/system/files/conference/usenixsecurity15/sec15-paper-melara.pdf.

### Hu, Hooshmand, Kalidhindi, Yang, and Popa (2021). Merkle²: A Low-Latency Transparency Log System.
Type: Systems paper with a full implementation and latency/scale benchmarks. Method: builds a forest of chronological Merkle trees nesting prefix-trees of child logs to decouple update latency from tree depth, benchmarked against prior transparency-log designs.
Key findings: propagates updates in roughly 1 second and supports on the order of 100x more users than prior transparency logs at comparable auditor cost, an explicit low-latency-vs-scale tradeoff evaluation for a Merkle-based transparency system.
Relevance to RQ: the most recent (2021) performance-focused Merkle transparency log in this cluster, directly evaluating proof-size/verification-time and latency tradeoffs as system parameters scale, the closest contemporary analogue to this paper's own E3 experiment design.
Quality assessment: published at IEEE Symposium on Security and Privacy (S&P) 2021, peer-reviewed, top-tier security venue. No DOI is cited for this entry: a DOI initially reported for this paper (10.1109/SP40001.2021.00018) was independently re-checked against Crossref during this report's verification pass and found to resolve to a different, unrelated paper, so it has been dropped rather than published incorrectly.
Potential use: Related Work (Cluster B, most-recent anchor citation), Experimental Methodology (E3), Discussion (contemporary comparator for measured proof-size/latency growth).
Verified via: direct fetch of the IACR ePrint preprint https://eprint.iacr.org/2021/453, confirming exact title, full 5-author list, and a note that the paper's target venue is IEEE S&P 2021 (pages 285-303 per that venue's proceedings, per independent search corroboration; not independently confirmed by direct fetch of an IEEE Xplore page, which returned no retrievable content during this pass).

### Cluster C: DP / k-Anonymity Systems with Budget Accounting

### Abadi, Chu, Goodfellow, McMahan, Mironov, Talwar, and Zhang (2016). Deep Learning with Differential Privacy.
Type: Systems/algorithms paper with an implemented, evaluated mechanism. Method: introduces the moments accountant, a tight composition method for tracking cumulative privacy loss (an explicit privacy-budget accountant) across many training steps of DP-SGD, evaluated empirically on real datasets.
Key findings: demonstrates the accuracy cost of a fixed privacy budget directly: 97% accuracy on MNIST and 73% on CIFAR-10 under specific (epsilon, delta) budgets, quantifying utility loss as a function of a tracked, enforced budget.
Relevance to RQ: the canonical worked example of a budget-accounting mechanism paired with an explicit accuracy-vs-privacy-budget measurement, the same evidentiary shape this paper's E4 experiment reports for its own Laplace-DP budget accountant (epsilon=0.5/query, epsilon_total=2.0/site).
Quality assessment: ACM CCS 2016, DOI-verified, one of the most cited papers in applied differential privacy.
Potential use: Related Work (Cluster C, anchor citation), Experimental Methodology and Results (E4 framing and comparator for accuracy-vs-budget reporting conventions).
DOI: 10.1145/2976749.2978318. Verified via Crossref API: title, all 7 authors, venue "Proceedings of the 2016 ACM SIGSAC Conference on Computer and Communications Security", pages 308-318, year 2016 all matched.

### Mironov (2017). Rényi Differential Privacy.
Type: Theoretical mechanism paper underlying modern budget accountants. Method: proposes Rényi differential privacy (RDP), a relaxed privacy definition admitting substantially tighter composition/accounting than the standard moments-accountant approach.
Key findings: RDP-based accounting is now the standard machinery underlying most production and research privacy-budget trackers, including the DP-SGD ecosystem built on Abadi et al. above.
Relevance to RQ: supplies the theoretical accounting machinery that modern budget accountants, including systems structurally similar to this paper's per-site epsilon_total=2.0 accountant, are typically built on.
Quality assessment: IEEE CSF 2017, DOI-verified, peer-reviewed, single-author, widely adopted in follow-on work.
Potential use: Related Work (Cluster C), System Architecture (theoretical grounding for the privacy-budget-accountant design choice).
DOI: 10.1109/CSF.2017.11. Verified via arXiv abstract page (https://arxiv.org/abs/1702.07476, title and sole author confirmed, explicit note of CSF 2017 pp. 263-275 publication) and independently re-confirmed via the Crossref API (`https://api.crossref.org/works/10.1109/CSF.2017.11`), which returned the identical title, author, venue, and pages.

### Hay, Machanavajjhala, Miklau, Chen, and Zhang (2016). Principled Evaluation of Differentially Private Algorithms using DPBench.
Type: Empirical benchmark paper. Method: establishes evaluation principles and an open benchmark (DPBench) for comparing DP algorithms' accuracy under varying privacy budgets and query workloads over range-query answering tasks.
Key findings: produces concrete accuracy-vs-epsilon curves across multiple DP mechanisms under a shared benchmark methodology, the same empirical framing (accuracy as a function of privacy budget) this cluster targets.
Relevance to RQ: directly precedents an accuracy-vs-epsilon empirical evaluation methodology for a DP system, the same evaluation shape as this paper's E4 experiment and a candidate methodological reference for how E4's curves are reported.
Quality assessment: ACM SIGMOD 2016, DOI-verified, peer-reviewed, top-tier database venue, still the standard reference for empirical DP-utility benchmarking.
Potential use: Related Work (Cluster C), Experimental Methodology (E4 methodological precedent).
DOI: 10.1145/2882903.2882931. Verified via Crossref API: title, all 5 authors, venue "Proceedings of the 2016 International Conference on Management of Data", pages 139-154, year 2016 all matched.

### Rogers, Subramaniam, Peng, Durfee, Lee, Kancha, Sahay, and Ahammad (2021). LinkedIn's Audience Engagements API: A Privacy Preserving Data Analytics System at Scale.
Type: Production systems paper. Method: describes a deployed budget-management service enforcing a strict differential-privacy budget on returned query results, built on the Pinot analytics platform, serving live audience-engagement queries at LinkedIn scale, and discusses utility-preservation strategies within the enforced budget.
Key findings: a real, production-grade privacy-budget accountant gating query release at industrial scale, with explicit engineering tradeoffs for keeping results useful within a fixed, enforced budget, the strongest real-world deployment analogue found in this cluster.
Relevance to RQ: structurally very close to this paper's own per-site budget accountant, the strongest evidence that "a budget accountant with a measured utility cost" is a real, deployed design pattern and not a purely theoretical one.
Quality assessment: Journal of Privacy and Confidentiality (TPDP 2020 special section), DOI-verified, peer-reviewed.
Potential use: Related Work (Cluster C, strongest anchor citation), Discussion (industrial-scale comparator for the epsilon_total=2.0 per-site budget design and its own utility tradeoffs found in E4).
DOI: 10.29012/jpc.782. Verified via Crossref API: title, all 8 authors, journal "Journal of Privacy and Confidentiality", volume 11, issue 3, year 2021 all matched.

### Abowd, Ashmead, Cumings-Menon, Garfinkel, Heineck, Heiss, Johns, Kifer, Leclerc, Machanavajjhala, Moran, Sexton, Spence, and Zhuravlev (2022). The 2020 Census Disclosure Avoidance System TopDown Algorithm.
Type: Large-scale deployed systems paper. Method: documents the largest deployed application of a formal privacy-budget-accounting framework (zero-concentrated DP) at national statistical-agency scale, including the explicit policy process for allocating privacy-loss budget across query tables and detailed accuracy/bias tradeoffs versus the pre-2020 disclosure-avoidance approach.
Key findings: a fixed, publicly scrutinized privacy budget spent across many coordinated releases produces measurable, non-trivial accuracy costs, documented and defended in detail against public and academic criticism.
Relevance to RQ: a large real-world case study of a nationally-scaled formal privacy budget and its accuracy cost, a useful contrast case for this paper's much smaller-scale, per-site budget (epsilon_total=2.0) system, and a citation that anchors the "privacy budget vs. community utility" tension SIDS 2026 itself raised as an open governance challenge.
Quality assessment: Harvard Data Science Review, DOI-verified, peer-reviewed, widely cited flagship case study in applied differential privacy policy.
Potential use: Introduction (motivating "privacy budget has a real, contested accuracy cost" framing), Related Work (Cluster C), Discussion (dialogue with SIDS 2026's own privacy-budget-vs-utility open question).
DOI: 10.1162/99608f92.529e3cb9. Verified via Crossref API: title, all 14 authors, journal "Harvard Data Science Review", year 2022 all matched, cross-checked against arXiv preprint https://arxiv.org/abs/2204.08986.

### Prasser, Eicher, Spengler, Bild, and Kuhn (2020). Flexible data anonymization using ARX—Current status and challenges ahead.
Type: Tool/systems paper. Method: describes ARX, an open-source anonymization tool implementing k-anonymity and related models (l-diversity, t-closeness, k-map) alongside a differential-privacy mode, with built-in utility metrics for evaluating the information-loss cost of a chosen anonymization configuration.
Key findings: an actively maintained, real k-anonymity tool with an explicit utility-cost evaluation layer bridging structural anonymization (k-anonymity) and formal DP, evaluated across multiple real-world anonymization scenarios.
Relevance to RQ: a direct comparison point for this paper's k=5 structural baseline stage, the k-anonymity half of its k-anonymity + Laplace-DP pipeline, and a real tool that combines both mechanisms the way this paper's own pipeline does.
Quality assessment: Software: Practice and Experience, DOI-verified, peer-reviewed, describes long-running (10+ year) real software.
Potential use: Related Work (Cluster C), System Architecture (comparator for combining k-anonymity and DP in one pipeline).
DOI: 10.1002/spe.2812. Verified via Crossref API: title, all 5 authors, journal "Software: Practice and Experience", volume 50, issue 7, pages 1277-1304, year 2020 all matched.

### Cluster D: Threshold Cryptography / Quorum Authorization for Data Governance

### Kokoris-Kogias, Alp, Gasser, Jovanovic, Syta, and Ford (2020). CALYPSO: Private Data Management for Decentralized Ledgers.
Type: Systems paper with a full implementation and latency benchmarks across quorum sizes. Method: builds a system for "on-chain secrets", confidential data whose release requires a threshold of a trustee committee, with an atomic, auditable access trace and explicit user-controlled consent management; benchmarks transaction latency as trustee-committee (quorum) size scales.
Key findings: measured transaction latency scales from roughly 0.2 to 8 seconds as the trustee/quorum size grows from 16 to 128, an explicit, measured tradeoff between committee (quorum) size, the security/robustness knob, and system latency/availability.
Relevance to RQ: the single strongest match in this cluster. A threshold committee gating data release with an auditable trace, and a measured tradeoff curve between quorum size and system performance/availability, directly parallel to this paper's own G1 experiment (quorum failure rate of the real 2-of-3 veto policy vs. a hypothetical stricter policy).
Quality assessment: Proceedings of the VLDB Endowment (PVLDB), DOI-verified, peer-reviewed, top-tier database/systems venue.
Potential use: Related Work (Cluster D, anchor citation), Experimental Methodology and Results (G1 direct comparator), Discussion (availability-vs-accountability tradeoff framing).
DOI: 10.14778/3436905.3436917. Verified via Crossref API: title, all 6 authors, venue "Proceedings of the VLDB Endowment", volume 14, issue 4, pages 586-599, year 2020 all matched, cross-checked against the official VLDB PDF (https://www.vldb.org/pvldb/vol14/p586-kogias.pdf) and IACR ePrint 2018/209.

### Raisaro, Troncoso-Pastoriza, Misbach, Sá Sousa, Pradervand, Missiaglia, Michielin, Ford, and Hubaux (2019). MedCo: Enabling Secure and Privacy-Preserving Exploration of Distributed Clinical and Genomic Data.
Type: Deployed systems paper. Method: an operational system in which decryption of pooled clinical/genomic query results requires collective participation of all federated clinical sites holding key shares, a threshold/collective-key mechanism where no single site can unilaterally release data; evaluated for latency and scalability across query types and site counts.
Key findings: a real, deployed system using threshold decryption as the actual consent/release gate for federated data-sharing across institutional stewards, with measured latency/scalability characteristics as the number of participating sites grows.
Relevance to RQ: structurally parallel to this paper's steward-quorum release gate (multiple stewards must jointly authorize a data release), though its published evaluation centers on latency/scalability rather than an explicit quorum-failure-rate-vs-quorum-size curve, a weaker match on the availability-tradeoff dimension specifically than CALYPSO.
Quality assessment: IEEE/ACM Transactions on Computational Biology and Bioinformatics, DOI-verified, peer-reviewed.
Potential use: Related Work (Cluster D), System Architecture (comparator for threshold-decryption-as-release-gate designs in a healthcare/institutional-steward context, close to this paper's own community-steward setting).
DOI: 10.1109/TCBB.2018.2854776. Verified via Crossref API: title, all 9 authors, journal "IEEE/ACM Transactions on Computational Biology and Bioinformatics", volume 16, issue 4, pages 1328-1341, year 2019 all matched.

### Zhao and Su (2024). A threshold traceable delegation authorization scheme for data sharing in healthcare.
Type: Cryptographic protocol paper with reported experiments. Method: proposes a threshold-based delegation-authorization scheme (TTDA) for electronic health record sharing, where multiple authorized delegates must jointly complete a threshold signature operation before an out-of-policy access request is granted, with accountability/traceability of which delegates participated; reports experiments on scheme practicability.
Key findings: demonstrates a working threshold-delegation authorization mechanism with reported performance/overhead experiments in a healthcare data-sharing context; whether these experiments include an explicit availability-vs-threshold-size tradeoff curve (as opposed to only latency/overhead benchmarks) could not be independently confirmed, since the ScienceDirect landing page blocked direct fetch during verification.
Relevance to RQ: directly on-topic (threshold cryptography plus delegation plus authorization plus healthcare data-sharing/consent), included as a supporting rather than anchor citation given the above confirmation gap.
Quality assessment: Computers & Security, DOI-verified via Crossref (title, both authors, journal, volume, article number, and year all confirmed as a single unambiguous top match), peer-reviewed. Flagged: the specific availability-tradeoff claim in the summary above is inferred from the abstract rather than independently confirmed against the full text.
Potential use: Related Work (Cluster D), noted with the confirmation caveat above rather than cited for a specific quantitative claim.
DOI: 10.1016/j.cose.2023.103686. Verified via Crossref API: title "A threshold traceable delegation authorization scheme for data sharing in healthcare", authors Jing Zhao and Qianqian Su, journal "Computers & Security", volume 139, article no. 103686, year 2024, all matched.

### MDPI Systems, venue-relevant (not mapped to a core cluster)

### Albalwy (2026). Zero-Knowledge-Based Policy Enforcement for Privacy-Preserving Cross-Institutional Health Data Sharing on Blockchain.
Type: Implemented systems paper, published in the paper's own target venue (MDPI Systems). Method: ZK-EHR, a decentralized access-control framework for cross-institutional electronic-health-record sharing that integrates zk-SNARK-based zero-knowledge proofs with blockchain smart contracts to verify policy compliance without revealing user roles, affiliations, or credentials; implemented with Python zk-circuits, Solidity smart contracts on a permissioned Ethereum chain, and RESTful APIs supporting HL7 FHIR interoperability.
Key findings: demonstrates that policy-compliance verification for cross-institutional health data access can be technically enforced and independently checked (via zero-knowledge proof verification) rather than merely asserted, without the access-control system needing to see the requester's identity.
Relevance to RQ: does not use threshold cryptography or quorum authorization, so it does not fit Cluster D's specific scope, and it has no privacy-budget accountant or Merkle-audit-log component, so it does not cleanly fit Clusters B or C either. It is included as a general Related Work / Discussion citation because it is a real, technically-verified example of the same broader move this paper makes: replacing an asserted governance property (policy compliance) with a cryptographically checkable one, in a health-data-sharing context comparable to this paper's own vulnerable-population setting, published in the target journal.
Quality assessment: peer-reviewed, MDPI Systems, DOI-verified, 2026 (very recent).
Potential use: Related Work (general framing, target-venue precedent), Discussion (comparator for "verified vs. asserted" governance properties in a health-data context).
DOI: 10.3390/systems14040385. Verified via Crossref API (`https://api.crossref.org/works/10.3390/systems14040385`): title, sole author Faisal Albalwy, journal Systems, volume 14, issue 4, article no. 385, year 2026 all matched.

## Literature Matrix

| Source | Cluster A (EDC/connectors) | Cluster B (Merkle/audit logs) | Cluster C (DP/k-anon budget) | Cluster D (threshold/quorum governance) | SIDS-2026-continuity |
|---|:---:|:---:|:---:|:---:|:---:|
| Dam et al. 2023 | X | | | | |
| Mügge et al. 2023 | X | | | | |
| Kalogeropoulos et al. 2023 | X | | | | |
| Norikane & Nishimura 2025 | X | | | | |
| Schneier & Kelsey 1999 | | X | | | |
| Crosby & Wallach 2009 | | X | | | |
| Laurie, Langley & Kasper 2013 (RFC 6962) | | X | | | |
| Melara et al. 2015 (CONIKS) | | X | | | |
| Hu et al. 2021 (Merkle^2) | | X | | | |
| Abadi et al. 2016 | | | X | | |
| Mironov 2017 | | | X | | |
| Hay et al. 2016 (DPBench) | | | X | | |
| Rogers et al. 2021 (LinkedIn) | | | X | | |
| Abowd et al. 2022 (Census TopDown) | | | X | | |
| Prasser et al. 2020 (ARX) | | | X | | |
| Kokoris-Kogias et al. 2020 (CALYPSO) | | | | X | |
| Raisaro et al. 2019 (MedCo) | | | | X | |
| Zhao & Su 2024 | | | | X | |
| Albalwy 2026 | | | | | |

Note: none of the 19 new sources overlap with SIDS 2026's existing 22-entry bibliography (no shared authors, systems, or venues), and the SIDS-2026-continuity column is left empty for all of them rather than stretched to claim a link. This is expected and reported honestly: the four new clusters were deliberately scoped to be technical neighbors one layer down from SIDS 2026's governance-and-policy-level related work, not restatements or extensions of specific SIDS 2026 citations. An earlier draft of this table marked Abowd et al. 2022 as a partial continuity link to SIDS 2026's Data Governance Act/Data Act citations on the grounds that both engage EU/US formal-privacy policy; on review this was judged too loose a connection (different jurisdiction, different regulatory instrument, no shared citation trail) to represent honestly as continuity, so it was removed rather than kept as a soft claim.

## Identified Gaps

1. **EDC performance evaluation is genuinely thin in the literature, including in the paper's own target venue.** No peer-reviewed source, in general search or in a dedicated search of MDPI Systems, was found with a rigorous quantitative EDC latency/throughput benchmark under load; the strongest Cluster A sources report real deployments (Mügge et al.), scalability experiments at a different layer (Kalogeropoulos et al.), or resilience-oriented simulation rather than connector-level measurement (Norikane and Nishimura, in MDPI Systems itself), but not a dedicated connector performance study. This is a real, citable gap given EDC's fast-growing adoption (Catena-X, Manufacturing-X, Mobility Data Space): the paper can note this directly rather than imply a body of comparator work exists.
2. **Threshold/quorum systems combining data governance, consent specifically, and a measured availability tradeoff are rare.** Most threshold-cryptography papers report latency/throughput benchmarks under varying threshold sizes, not a quorum-size-vs-failure-rate curve of the kind this paper's G1 experiment produces. Only CALYPSO clears this bar cleanly; MedCo and Zhao & Su are real and topically adjacent but do not (as confirmed) report the same tradeoff shape. This paper's G1 result is consequently one of the more direct empirical availability-vs-security tradeoff measurements in this specific application area (community/steward data governance), not just in the broader threshold-cryptography literature.
3. **No source in any cluster combines all three mechanisms this paper measures together (audit-chain, budget accountant, and quorum) in one evaluated system.** Each cluster's strongest sources address one mechanism in isolation (CALYPSO for quorum, Merkle^2/CONIKS for audit logs, Rogers et al./Abadi et al. for budget accounting). This supports the paper's own claim that its contribution is measuring several sovereignty mechanisms together under one built system, not any single mechanism alone.
4. **Proof-size/verification-time evaluation for audit-log systems tends to report scale (users, log size) rather than epoch-size as the swept variable.** CONIKS and Merkle^2 sweep user/system scale; this paper's E3 experiment sweeps epoch size specifically. This is a genuine methodological gap the paper's E3 section can note as a point of departure rather than direct precedent.
5. **Differential-privacy budget-accounting literature splits between algorithmic/theoretical accounting papers (Abadi et al., Mironov) and production deployment reports (Rogers et al., Abowd et al.), with comparatively few mid-scale, small-population empirical evaluations of the kind this paper's E4 experiment represents** (a single per-site budget of epsilon_total=2.0 against realistically small service-site populations). DPBench (Hay et al.) is the closest methodological precedent but at range-query workloads rather than small-population social-service aggregates. This gap directly supports SIDS 2026's own stated open concern (differential privacy "can render aggregate statistics analytically meaningless at the low population volumes of individual service sites") as a real, still-underaddressed empirical question.

## Recommended Sources by Paper Section

| Section | Recommended sources |
|---|---|
| Introduction | Abowd et al. 2022 (Census TopDown, motivating that formal privacy budgets carry a real, contested accuracy cost at scale) |
| Related Work | All 19: Dam et al. 2023, Mügge et al. 2023, Kalogeropoulos et al. 2023, Norikane & Nishimura 2025 (Cluster A); Schneier & Kelsey 1999, Crosby & Wallach 2009, Laurie/Langley/Kasper 2013, Melara et al. 2015, Hu et al. 2021 (Cluster B); Abadi et al. 2016, Mironov 2017, Hay et al. 2016, Rogers et al. 2021, Abowd et al. 2022, Prasser et al. 2020 (Cluster C); Kokoris-Kogias et al. 2020, Raisaro et al. 2019, Zhao & Su 2024 (Cluster D); Albalwy 2026 (venue-relevant, unmapped) |
| System Architecture and Implementation | Mügge et al. 2023, Kalogeropoulos et al. 2023, Norikane & Nishimura 2025 (connector/data-space deployment comparators); Schneier & Kelsey 1999, Laurie/Langley/Kasper 2013 (audit-chain design precedent); Mironov 2017, Prasser et al. 2020 (privacy-pipeline design grounding); Kokoris-Kogias et al. 2020, Raisaro et al. 2019 (steward-quorum design precedent) |
| Experimental Methodology | Crosby & Wallach 2009, Melara et al. 2015, Hu et al. 2021 (E3 proof-size/verification-time methodology); Abadi et al. 2016, Hay et al. 2016, Rogers et al. 2021 (E4 accuracy-vs-budget methodology); Kokoris-Kogias et al. 2020 (G1 quorum-size-vs-availability methodology) |
| Discussion | Rogers et al. 2021, Abowd et al. 2022 (privacy-budget-vs-utility, dialogue with SIDS 2026's Open Governance Challenges section); Kokoris-Kogias et al. 2020 (availability-vs-accountability tradeoff, dialogue with G1 findings); Albalwy 2026 (verified-vs-asserted governance framing); Identified Gaps 1-5 above (framing this paper's contribution against the thinness found in each cluster, including in the paper's own target venue) |
