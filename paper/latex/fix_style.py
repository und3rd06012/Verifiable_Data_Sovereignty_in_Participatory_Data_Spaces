#!/usr/bin/env python3
# 08-future-work: retitle subsection and rewrite the three questions + Whether sentences
fn = '08-future-work.tex'
with open(fn) as f:
    t = f.read()

t = t.replace(r'\subsection{Whether Sovereignty Changes What Happens}',
              r'\subsection{From Sovereignty Mechanisms to Service Decisions}')

t = t.replace(
'SIDS 2026 posed three research questions for the pilot: whether steward sovereignty controls designed with Greek migrants transfer across distinct legal frameworks and cultural norms. Whether observable sovereignty mechanisms replicate the trust and participation effects measured with participants who did not co-design the system. And whether community-governed participatory data actually influences service decisions, measured against control sites relying only on traditional administrative records. The third question is the one the pilot was designed to answer and the one this paper cannot touch: nothing in Chapter 5 speaks to whether a service provider changes a decision because the data behind it passed through a community-governed pipeline rather than an administrative one.',
'SIDS 2026 posed three research questions for the pilot. Do steward-sovereignty controls designed with Greek migrants transfer across legal frameworks and cultural norms. Do observable sovereignty mechanisms replicate the trust and participation effects measured with participants who did not co-design the system. Does community-governed participatory data actually influence service decisions, measured against control sites that rely only on traditional administrative records. The third question is the one the pilot was designed to answer and the one this paper cannot touch. Nothing in Chapter 5 speaks to whether a service provider changes a decision because the data behind it passed through a community-governed pipeline rather than an administrative one.')

t = t.replace(
"Whether stewards given this information choose materially different budgets, and whether communities judge the resulting aggregates more useful, is an empirical question this paper's simulated curves inform but cannot answer.",
"An empirical question this paper's simulated curves inform but cannot answer is whether stewards given this information choose materially different budgets and whether communities judge the resulting aggregates more useful.")

with open(fn, 'w') as f:
    f.write(t)
print('08 ok')

# 02-related-work: fix Whether sentence
fn = '02-related-work.tex'
with open(fn) as f:
    t = f.read()
t = t.replace(
'Whether those experiments include an explicit availability-versus-threshold-size tradeoff curve, as opposed to latency or overhead benchmarks alone, is unclear from the published description.',
'The published description does not make clear whether those experiments include an explicit availability-versus-threshold-size tradeoff curve rather than latency or overhead benchmarks alone.')
with open(fn, 'w') as f:
    f.write(t)
print('02 ok')

# manuscript.tex: strip ; -- --- everywhere in prose/metadata
fn = 'manuscript.tex'
with open(fn) as f:
    t = f.read()

t = t.replace(r'\doinum{10.3390/------}', r'\doinum{10.3390/xxxx}')
t = t.replace(r'\hreflink{https://doi.org/10.3390/------}', r'\hreflink{https://doi.org/10.3390/xxxx}')
t = t.replace(r'\AuthorCitation{Katrakazas, P.; Ntib, A.}', r'\AuthorCitation{Katrakazas, P. and Ntib, A.}')
t = t.replace('Athens, Greece; katrakazas.p@kmop.org', 'Athens, Greece, katrakazas.p@kmop.org')
t = t.replace('London, ON, Canada; antib@uwo.ca', 'London, ON, Canada, antib@uwo.ca')
t = t.replace(r'\keyword{data sovereignty; Data Spaces; Eclipse Dataspace Components; quorum authorization; differential privacy; tamper-evident audit logs; mechanism-level verification}',
              r'\keyword{data sovereignty, Data Spaces, Eclipse Dataspace Components, quorum authorization, differential privacy, tamper-evident audit logs, mechanism-level verification}')
t = t.replace(r'\authorcontributions{Conceptualization, P.K. and A.N.; methodology, A.N.; software, A.N.; validation, A.N.; formal analysis, A.N.; investigation, A.N.; data curation, A.N.; writing---original draft preparation, A.N.; writing---review and editing, P.K. and A.N.; visualization, A.N.; supervision, P.K.; project administration, P.K.; funding acquisition, P.K. All authors have read and agreed to the published version of the manuscript.}',
              r'\authorcontributions{Conceptualization, P.K. and A.N. Methodology, A.N. Software, A.N. Validation, A.N. Formal analysis, A.N. Investigation, A.N. Data curation, A.N. Writing, original draft preparation, A.N. Writing, review and editing, P.K. and A.N. Visualization, A.N. Supervision, P.K. Project administration, P.K. Funding acquisition, P.K. All authors have read and agreed to the published version of the manuscript.}')
t = t.replace('This study did not collect new data from human participants; it reports the design',
              'This study did not collect new data from human participants. It reports the design')
t = t.replace('The funders had no role in the design of the study; in the collection, analyses, or interpretation of data; in the writing of the manuscript; or in the decision to publish the results.',
              'The funders had no role in the design of the study, in the collection, analyses, or interpretation of data, in the writing of the manuscript, or in the decision to publish the results.')

with open(fn, 'w') as f:
    f.write(t)
print('manuscript ok')
