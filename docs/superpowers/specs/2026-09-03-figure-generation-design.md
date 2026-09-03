# Figure Generation: Design

## 1. Purpose and scope

The final phase-7 deliverable, closing the parent spec's own gate row
("E1, E4, G1, G2, figure generation") once all six experiments' real,
committed CSVs (E1, E2, E3, E4, G1, G2) are turned into the actual
reportable figures. Per the parent spec (section 5): "Everything runs from
fixed seeds, results land as CSV, and Python generates figures from those
files so a figure cannot drift from the numbers behind it." Per section 6's
repository layout, this lives at `experiments/analysis/`, "Python
statistics and figures", a directory that does not exist anywhere in this
repository yet. This is the first Python code in a repository that has so
far been entirely TypeScript and Java (aside from two small, dependency-free
utility scripts under `tools/`).

This sub-project produces the figures. It does not write the paper (phase
8, not yet started) and does not decide how each figure gets captioned or
discussed; it only needs to turn six already-reproducible CSVs into seven
correct, legible images.

## 2. Repository layout

```
experiments/analysis/
  requirements.txt       matplotlib and pandas, pinned versions
  common.py               shared plot styling and CSV-loading helpers
  e1_figure.py
  e2_figure.py
  e3_figure.py
  e4_figure.py
  g1_figure.py
  g2_figure.py
  generate_all.py         runs all six scripts in sequence
  figures/                 output directory (PDF + PNG per script)
```

A Python virtual environment at `experiments/analysis/.venv/` (already
covered by the repository's existing `.gitignore` entry for `.venv/`),
created with the system `python3` (confirmed present: Python 3.12.3).
`requirements.txt` pins exact versions of `matplotlib` and `pandas` as
resolved at implementation time; no other third-party package is added.
Each of the six per-experiment scripts is independently runnable
(`python e1_figure.py`, from within `experiments/analysis/` with the venv
active) and reads only already-committed CSVs under `experiments/results/`;
none of them re-runs any experiment or needs live infrastructure.

## 3. Shared infrastructure (`common.py`)

Two small, reused pieces, kept in one file since neither is large enough to
warrant its own module:

- **A plot-styling setup function**, applied once at the top of every
  script (`matplotlib.rcParams` or an equivalent style context): a
  consistent font size, figure DPI (150 for the PNG preview; PDF is
  vector and DPI-independent), and a fixed, colorblind-safe categorical
  color sequence reused across all seven figures so a reader flipping
  between them sees the same color mean the same thing wherever it
  recurs (e.g., if both E4 and G2 plot something by epsilon, epsilon's
  own color mapping stays consistent).
- **A CSV-loading helper**, a thin wrapper over `pandas.read_csv` doing
  nothing beyond that (no silent type coercion, no filling of missing
  values); each script is responsible for whatever grouping/aggregation
  its own data actually needs, per section 4 below.

## 4. Per-experiment figures

Each figure is saved as both `experiments/analysis/figures/<name>.pdf`
(the primary, vector output, for the eventual paper) and
`experiments/analysis/figures/<name>.png` (a raster preview at 150 DPI,
the only practical way to visually verify a plot's correctness during
this sub-project's own implementation, since there is no meaningful
pixel-level assertion for a chart).

### 4.1 E1: consent filter cost (`e1_figure.py` → `e1-crossover.pdf`)

Reads all three of `experiments/results/e1/{baseline,structural,signature}.csv`
(columns: `seed,trial,batch_size,field_count,grant_set_size,wall_time_ms,
throttle_cpus,throttle_memory_mb,host_cpu_model`), tagging each with its
own arm name from the filename. These are raw per-trial rows (unlike every
other experiment's own CSV, already pre-aggregated); this script performs
its own aggregation: group by `(arm, batch_size, field_count)`, pooling
across every `trial` and `grant_set_size` value within that group, and
compute the median and interquartile range of `wall_time_ms` per group (the
same linear-interpolation convention used throughout every Java/TypeScript
experiment harness this session, applied here via `numpy.percentile` with
`interpolation='linear'`, matplotlib's own dependency already providing
this, no additional package needed).

Three small-multiple panels, one per `field_count` value (1, 5, 20, the
three values E1's own sweep used). Each panel: x-axis `batch_size` (log
scale, spanning the swept 10 to 10,000), y-axis median `wall_time_ms` (log
scale, since the three arms' costs differ by roughly two orders of
magnitude per the real committed data), one colored line per arm
(baseline/structural/signature) with a shaded IQR band. This is E1's own
"crossover" figure per the parent spec's own framing (4.3): the point
where signature verification's cost overtakes structural validation's,
visible directly as where the signature line's shaded band starts
separating meaningfully from the other two.

### 4.2 E2: revocation propagation (`e2_figure.py` → `e2-propagation.pdf`)

Reads `experiments/results/e2/partition.csv` directly (already
pre-aggregated, five rows, one per swept `online_probability` value). One
panel, x-axis `online_probability`, y-axis ticks, two colored lines
(`propagation_median_ticks`, `consistency_median_ticks`) each with a
shaded IQR band from `propagation_iqr_ticks`/`consistency_iqr_ticks`. No
aggregation needed; this script is the simplest of the seven, essentially
a direct plot of the CSV's own already-final columns.

### 4.3 E3: audit tamper detection cost (`e3_figure.py` → `e3-verification-cost.pdf`)

Reads all three of `experiments/results/e3/{proof-size,verify-inclusion-time,
verify-chain-time}.csv`. `proof-size.csv` (`seed,epoch_size,leaf_index,
proof_steps,proof_bytes`) and `verify-inclusion-time.csv` (`seed,epoch_size,
leaf_index,elapsed_ms,throttle_cpus,throttle_memory_mb,host_cpu_model`) are
raw per-leaf rows needing the same group-by-and-aggregate treatment as E1:
group by `epoch_size`, median (and, for the time CSV, IQR) across every
`leaf_index` value. `verify-chain-time.csv` (`seed,chain_length,elapsed_ms,
throttle_cpus,throttle_memory_mb,host_cpu_model`) already has exactly one
row per swept `chain_length` value (confirmed: 5 data rows for 5 swept
chain lengths, no repeated trials), so it needs no aggregation, only a
direct plot.

Three panels in one figure: left, `proof_bytes` (median) vs `epoch_size`
(log-log, since epoch_size spans a wide sweep); middle, `elapsed_ms`
(median, with an IQR band) vs `epoch_size` for inclusion-proof
verification (log x-axis, linear or log y depending on what the real
data's own range calls for, decided empirically when the real numbers are
in hand); right, a smaller panel, `elapsed_ms` vs `chain_length` for
chain-of-custody verification (linear axes, since this sweep is small,
only 5 points).

### 4.4 E4: privacy budget versus utility (`e4_figure.py` → `e4-utility.pdf`)

Reads `experiments/results/e4/utility.csv` directly (already
pre-aggregated, includes both the original all-inclusive statistic
columns and the `surviving_*` columns this session's own final-review fix
wave added specifically to make this figure meaningful; see section 6 for
why the plain columns are explicitly not used here). Filters to
`skew == 'uniform'` only (skewed left as a smaller, secondary figure if
wanted later, out of this spec's own scope per section 6).

Four small-multiple panels, one per swept `population` value (30, 100, 200,
300). Each panel: x-axis `epsilon` (log scale, spanning the swept 0.1 to
2.0, all eight swept values), y-axis `surviving_median_relative_error`,
one colored line per `k` value (3, 5, 7, 10), with a shaded IQR band
(`surviving_iqr_relative_error`) shown for one representative `k` value
only (the middle one, `k=5`), to keep four overlapping bands from making
the panel unreadable while still conveying the real spread. This is the
figure the parent spec calls "the article's most useful figure" and the
one the whole surviving-statistic fix wave existed to make legible: the
real accuracy-versus-epsilon tradeoff, not the flat sentinel line the
original all-inclusive statistic produced before that fix.

### 4.5 G1: quorum availability and deadlock (`g1_figure.py` → `g1-availability.pdf`)

Reads `experiments/results/g1/availability.csv` directly (already
pre-aggregated, 135 data rows). Filters to `veto_share == 0.0` only, to
isolate the real-versus-hypothetical comparison cleanly from the added
complexity a nonzero veto share would introduce into the same panels
(veto_share's own effect is a secondary result, out of this figure's own
scope per section 6).

Three small-multiple panels, one per swept `quorum_fraction` value (0.3,
0.5, 0.7). Each panel: x-axis `p_act` (0.1 to 0.9), y-axis
`starved_rate` (0.0 to 1.0), two lines, `arm_a_starved_rate` (real,
solid) and `arm_b_starved_rate` (hypothetical veto-blocks, dashed),
same color, differing only by line style so the real-versus-hypothetical
distinction reads as one consistent visual language across all three
panels. Directly answers the parent spec's own framing (5): "whether 2 of
3 with rotation is workable at community scale or whether it stalls",
and shows how much worse the hypothetical veto-blocking policy would be
if it were ever actually implemented.

### 4.6 G2: consent heterogeneity versus aggregate utility (`g2_figure.py` → `g2-heterogeneity.pdf`)

Reads `experiments/results/g2/heterogeneity.csv` directly (already
pre-aggregated, includes the same `surviving_*` column pair E4 has, added
by this session's own G1+G2 final-review fix wave for the identical
reason). Filters to `population == 100` only (population=300 left as a
secondary figure if wanted later, out of this spec's own scope per section
6).

Four small-multiple panels, one per swept `epsilon` value (0.25, 0.5,
1.0, 2.0). Each panel: x-axis `h` (the heterogeneity parameter, 0.0 to
1.0), y-axis relative error, two colored lines,
`surviving_coverage_median_relative_error` (flat, the privacy mechanism's
own noise error) and `surviving_exclusion_median_relative_error` (rising
with `h`, the exclusion bias heterogeneous consent introduces on its own),
each with a shaded IQR band. This is the figure that isolates the
sub-project's actual headline finding: exclusion bias grows with
heterogeneity while ordinary differential-privacy noise does not, a
distinction the raw, non-surviving statistic columns cannot show (both are
pinned at the sentinel value across most of the swept grid, per this
session's own already-documented finding).

## 5. `generate_all.py`

A short driver script, no logic of its own beyond calling each of the six
per-experiment scripts' own `main()` function (or, if a script is written
as an importable module with a `main()` guarded by `if __name__ ==
'__main__':`, calling that function directly rather than shelling out),
printing which figure it is generating before each call, and exiting
non-zero if any one fails, so a single command
(`python experiments/analysis/generate_all.py`) regenerates every figure
from the current committed CSVs in one pass, matching this project's own
"a figure cannot drift from the numbers behind it" requirement: rerunning
this script after any CSV changes always reproduces every figure fresh.

## 6. Non-goals

- No changes to any experiment's own harness or committed CSV. This
  sub-project only reads `experiments/results/*.csv` files that already
  exist; it does not regenerate or modify any of them.
- No figures for E1's `grant_set_size` (decoy count) dimension, E4/G2's
  `skew`/`population=300` secondary slices, or G1's nonzero `veto_share`
  slices. Each of these is a real, swept dimension with real data behind
  it, deliberately left out of this spec's seven figures to keep each one
  legible and focused on its own headline question; a secondary or
  appendix figure for any of these remains possible as a future,
  separately-scoped addition, not part of this sub-project.
- No LaTeX/paper integration. This sub-project produces standalone PDF/PNG
  files; how they get embedded into the manuscript is phase 8's own
  concern.
- No statistical significance testing, confidence intervals, or any
  inferential statistics beyond median/IQR (already this project's own
  established convention throughout every experiment's own harness). This
  sub-project visualizes what the experiments already computed; it does
  not compute anything new beyond the aggregation section 4 already
  specifies for E1/E3's raw CSVs.
- No interactive/HTML output (e.g. plotly). Static PDF/PNG only, matching
  what a LaTeX paper actually needs.

## 7. Global constraints carried from the parent project

- No em-dashes in code, docs, or commits.
- No LLM/AI-tooling attribution in commits.
- No emojis in code, logs, or figures.
- Every figure must be regenerable from the already-committed CSVs alone,
  with no network access, no live infrastructure, and no randomness of its
  own (this sub-project introduces no new seeded computation; it only
  aggregates and plots numbers other sub-projects already computed
  deterministically).
- Before writing each script's full aggregation/plotting logic, verify the
  exact real shape of its input CSV(s) directly (row counts, the exact set
  of distinct values in each swept column) rather than assuming the
  numbers given in section 4 above, the same live-verification discipline
  this project has applied to every other sub-project's own live
  infrastructure, adapted here to mean "the real committed data" rather
  than "a live running service."
